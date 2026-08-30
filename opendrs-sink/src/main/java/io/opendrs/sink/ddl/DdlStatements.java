package io.opendrs.sink.ddl;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Filters MySQL session / catalog statements that Debezium emits during schema snapshot
 * ({@code SET character_set_server=...}, {@code USE db}, {@code DROP/CREATE DATABASE}) and that
 * PostgreSQL cannot execute. Leftover backticks mean the converter did not rewrite the statement.
 */
public final class DdlStatements {

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
