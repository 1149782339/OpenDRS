package io.opendrs.engine.schema.convert;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Filters MySQL session / catalog statements that Debezium emits during schema snapshot
 * ({@code SET character_set_server=...}, {@code USE db}, {@code DROP/CREATE DATABASE}) and that
 * PostgreSQL cannot execute. Leftover backticks mean the converter did not rewrite the statement.
 *
 * <p>Also provides structure-migration helpers: MySQL teach/dump scripts often emit a plain
 * {@code CREATE TABLE} then {@code ALTER TABLE … PARTITION BY}. PostgreSQL has no such ALTER, but
 * folding the partition clause onto the CREATE lets {@code MySqlToPGParserListener}'s existing
 * CREATE partition rewriter (RANGE/LIST/HASH) run. Isolated {@code ALTER … PARTITION BY} (CDC) is
 * left alone for the converter to discard with INFO.
 */
public final class DdlStatements {

    private static final Pattern CREATE_TABLE_NAME = Pattern.compile(
            "(?is)^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(.+?)\\s*\\(");

    private static final Pattern ALTER_PARTITION_BY_ONLY = Pattern.compile(
            "(?is)^\\s*ALTER\\s+TABLE\\s+(?:IGNORE\\s+)?(.+?)\\s+(PARTITION\\s+BY\\b.*)$");

    private DdlStatements() {
    }

    public static List<String> executableForPostgres(List<String> statements) {
        List<String> executable = new ArrayList<>();
        if (statements == null) {
            return executable;
        }
        for (String statement : statements) {
            if (statement == null || statement.isBlank()) {
                continue;
            }
            if (isMysqlSessionOnly(statement)) {
                continue;
            }
            executable.add(statement);
        }
        return executable;
    }

    public static boolean isMysqlSessionOnly(String sql) {
        String trimmed = stripLeadingComments(sql).trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        String upper = trimmed.toUpperCase(Locale.ROOT);
        return upper.startsWith("SET ")
                || upper.startsWith("SET\t")
                || upper.startsWith("SET\n")
                || upper.startsWith("USE ")
                || upper.startsWith("USE\t")
                || upper.equals("USE")
                || upper.startsWith("SELECT @@")
                || upper.startsWith("DROP DATABASE")
                || upper.startsWith("CREATE DATABASE")
                || upper.startsWith("ALTER DATABASE")
                || upper.startsWith("DROP SCHEMA")
                || upper.startsWith("CREATE SCHEMA")
                || trimmed.contains("`");
    }

    /**
     * Splits a MySQL DDL script on semicolons outside quotes / backticks / comments.
     * Semicolons inside {@code BEGIN … END} compound bodies (routines / triggers) are kept —
     * a naive split there would shatter DECLARE / SET / RETURN fragments and break ANTLR.
     * Not a full MySQL client parser — enough for structure-migration CREATE/ALTER scripts and
     * single-routine DDL that still contains internal {@code ;}.
     */
    public static List<String> splitStatements(String sql) {
        List<String> statements = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            return statements;
        }
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        boolean inLineComment = false;
        boolean inBlockComment = false;
        int beginDepth = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
            if (inLineComment) {
                current.append(c);
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                current.append(c);
                if (c == '*' && next == '/') {
                    current.append(next);
                    i++;
                    inBlockComment = false;
                }
                continue;
            }
            if (!inSingle && !inDouble && !inBacktick) {
                if (c == '-' && next == '-') {
                    current.append(c).append(next);
                    i++;
                    inLineComment = true;
                    continue;
                }
                if (c == '#') {
                    current.append(c);
                    inLineComment = true;
                    continue;
                }
                if (c == '/' && next == '*') {
                    current.append(c).append(next);
                    i++;
                    inBlockComment = true;
                    continue;
                }
                if (isIdentStart(c)) {
                    int start = i;
                    int end = i + 1;
                    while (end < sql.length() && isIdentPart(sql.charAt(end))) {
                        end++;
                    }
                    String word = sql.substring(start, end);
                    String upper = word.toUpperCase(Locale.ROOT);
                    if ("BEGIN".equals(upper)) {
                        beginDepth++;
                    } else if ("END".equals(upper) && !isEndCloserKeyword(sql, end)) {
                        if (beginDepth > 0) {
                            beginDepth--;
                        }
                    }
                    current.append(word);
                    i = end - 1;
                    continue;
                }
                if (c == ';' && beginDepth == 0) {
                    String stmt = current.toString().trim();
                    if (!stmt.isEmpty()) {
                        statements.add(stmt);
                    }
                    current.setLength(0);
                    continue;
                }
            }
            if (c == '\'' && !inDouble && !inBacktick) {
                if (inSingle && next == '\'') {
                    current.append(c).append(next);
                    i++;
                    continue;
                }
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle && !inBacktick) {
                inDouble = !inDouble;
            } else if (c == '`' && !inSingle && !inDouble) {
                inBacktick = !inBacktick;
            }
            current.append(c);
        }
        String tail = current.toString().trim();
        if (!tail.isEmpty()) {
            statements.add(tail);
        }
        return statements;
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }

    /**
     * {@code END IF} / {@code END LOOP} / {@code END WHILE} / {@code END REPEAT} / {@code END CASE}
     * close those constructs, not a {@code BEGIN} block — leave {@code beginDepth} alone.
     */
    private static boolean isEndCloserKeyword(String sql, int afterEnd) {
        int i = afterEnd;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        if (i >= sql.length() || !isIdentStart(sql.charAt(i))) {
            return false;
        }
        int end = i + 1;
        while (end < sql.length() && isIdentPart(sql.charAt(end))) {
            end++;
        }
        String word = sql.substring(i, end).toUpperCase(Locale.ROOT);
        return "IF".equals(word)
                || "LOOP".equals(word)
                || "WHILE".equals(word)
                || "REPEAT".equals(word)
                || "CASE".equals(word);
    }

    /**
     * Structure-migration P0-a: when a plain {@code CREATE TABLE t (...)} is followed (not
     * necessarily immediately) by {@code ALTER TABLE t PARTITION BY ...} with no other ALTER
     * clauses, splice the partition clause onto the CREATE and drop the ALTER.
     *
     * <p>Isolated {@code ALTER … PARTITION BY} with no matching CREATE in {@code statements} is
     * preserved so the converter can keep discarding it (CDC). CREATE tables that already contain
     * {@code PARTITION BY} are left unchanged.
     */
    public static List<String> mergeCreateTableWithAlterPartitionBy(List<String> statements) {
        if (statements == null || statements.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(statements.size());
        for (String raw : statements) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String stmt = stripLeadingComments(raw).trim();
            Matcher alter = ALTER_PARTITION_BY_ONLY.matcher(stmt);
            if (alter.matches() && isPartitionByOnlyAlter(stmt)) {
                String tableKey = normalizeTableName(alter.group(1));
                String partitionClause = alter.group(2).trim();
                int createIndex = indexOfMergeableCreate(out, tableKey);
                if (createIndex >= 0) {
                    out.set(createIndex, appendPartitionClause(out.get(createIndex), partitionClause));
                    continue;
                }
            }
            out.add(stmt);
        }
        return out;
    }

    /**
     * {@code ALTER TABLE t PARTITION BY ...} with no ADD/DROP/REORGANIZE/… partition ops and no
     * other alter specifications mixed in.
     */
    static boolean isPartitionByOnlyAlter(String stmt) {
        String upper = stmt.toUpperCase(Locale.ROOT);
        if (!upper.contains("PARTITION BY")) {
            return false;
        }
        // ADD/DROP/TRUNCATE/REORGANIZE/EXCHANGE/REMOVE are alterSpecification forms, not trailing
        // PARTITION BY. If they appear, this is not a pure "attach partitioning" statement.
        return !upper.matches("(?s).*\\b(ADD|DROP|TRUNCATE|REORGANIZE|EXCHANGE|COALESCE|REBUILD|"
                + "CHECK|OPTIMIZE|REPAIR|ANALYZE|DISCARD|IMPORT|REMOVE|UPGRADE)\\s+PARTITION\\b.*")
                && !upper.contains("REMOVE PARTITIONING");
    }

    private static int indexOfMergeableCreate(List<String> out, String tableKey) {
        for (int i = out.size() - 1; i >= 0; i--) {
            String candidate = out.get(i);
            Matcher create = CREATE_TABLE_NAME.matcher(candidate);
            if (!create.find()) {
                continue;
            }
            if (!tableNamesMatch(tableKey, normalizeTableName(create.group(1)))) {
                continue;
            }
            if (candidate.toUpperCase(Locale.ROOT).contains("PARTITION BY")) {
                return -1;
            }
            return i;
        }
        return -1;
    }

    private static String appendPartitionClause(String createSql, String partitionClause) {
        String base = createSql.trim();
        while (base.endsWith(";")) {
            base = base.substring(0, base.length() - 1).trim();
        }
        String clause = partitionClause.trim();
        while (clause.endsWith(";")) {
            clause = clause.substring(0, clause.length() - 1).trim();
        }
        return base + "\n" + clause;
    }


    /** True when keys are equal, or share the same trailing table segment (schema-qualified vs bare). */
    static boolean tableNamesMatch(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        String aTail = a.substring(a.lastIndexOf('.') + 1);
        String bTail = b.substring(b.lastIndexOf('.') + 1);
        return aTail.equals(bTail);
    }

    /**
     * Strips schema/catalog quotes and normalizes to a comparable {@code catalog.schema.table} /
     * {@code schema.table} / {@code table} key (case-insensitive).
     */
    static String normalizeTableName(String rawName) {
        String[] parts = rawName.trim().split("\\s*\\.\\s*");
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            String p = part.trim();
            if ((p.startsWith("`") && p.endsWith("`"))
                    || (p.startsWith("\"") && p.endsWith("\""))
                    || (p.startsWith("'") && p.endsWith("'"))) {
                p = p.substring(1, p.length() - 1);
            }
            if (key.length() > 0) {
                key.append('.');
            }
            key.append(p.toLowerCase(Locale.ROOT));
        }
        return key.toString();
    }

    static String stripLeadingComments(String sql) {
        String current = sql.strip();
        while (current.startsWith("/*")) {
            int end = current.indexOf("*/");
            if (end < 0) {
                break;
            }
            current = current.substring(end + 2).strip();
        }
        while (current.startsWith("--") || current.startsWith("#")) {
            int newline = current.indexOf('\n');
            if (newline < 0) {
                return "";
            }
            current = current.substring(newline + 1).strip();
        }
        return current;
    }
}
