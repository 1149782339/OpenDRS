package io.opendrs.engine.jdbc.deferred;

import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.jdbc.JdbcConnection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL catalog reader. The key definitions are taken verbatim from {@code SHOW CREATE TABLE},
 * which is the only source that renders every detail MySQL keeps (prefix lengths, {@code DESC}
 * parts, functional key parts, {@code FULLTEXT ... WITH PARSER}, {@code SPATIAL}, {@code USING},
 * comments, {@code INVISIBLE}); each line becomes an {@code ALTER TABLE ... ADD <line>}.
 *
 * <p>Foreign keys are re-added on a session with {@code FOREIGN_KEY_CHECKS=0}: MySQL then neither
 * scans the child nor needs the parent rows, so the add is metadata-only.
 */
public final class MySqlObjectCatalog implements TargetObjectCatalog {

    private static final String IDENT = "`(?:[^`]|``)+`";
    private static final Pattern KEY_LINE = Pattern.compile(
            "^(UNIQUE KEY|KEY|FULLTEXT KEY|SPATIAL KEY)\\s+(" + IDENT + ")\\s*\\((.*)$");
    private static final Pattern FK_LINE = Pattern.compile(
            "^CONSTRAINT\\s+(" + IDENT + ")\\s+FOREIGN KEY\\s*\\(.*$");
    private static final Pattern REFERENCES = Pattern.compile(
            "REFERENCES\\s+(" + IDENT + ")(\\s*\\.\\s*" + IDENT + ")?");
    private static final Pattern FIRST_COLUMN = Pattern.compile("^\\s*(" + IDENT + ")");

    static final String REFERENCING_SQL = """
            SELECT TABLE_SCHEMA, TABLE_NAME, CONSTRAINT_NAME, REFERENCED_COLUMN_NAME
              FROM information_schema.KEY_COLUMN_USAGE
             WHERE REFERENCED_TABLE_SCHEMA = ? AND REFERENCED_TABLE_NAME = ?""";

    static final String INDEX_EXISTS_SQL = """
            SELECT 1 FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND INDEX_NAME = ? LIMIT 1""";

    static final String FOREIGN_KEY_EXISTS_SQL = """
            SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
             WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? AND CONSTRAINT_NAME = ?
               AND CONSTRAINT_TYPE = 'FOREIGN KEY'""";

    @Override
    public DatabaseType type() {
        return DatabaseType.MYSQL;
    }

    /** A foreign key (from anywhere) that references an in-scope table, per referenced column. */
    record ReferencingRow(String childSchema, String childTable, String constraintName, String referencedColumn) {
    }

    @Override
    public List<DeferredObject> capture(JdbcConnection target, Collection<TableId> tables) throws SQLException {
        Set<String> scope = scopeKeys(tables);
        List<DeferredObject> objects = new ArrayList<>();
        for (TableId table : tables) {
            String ddl = target.queryOne("SHOW CREATE TABLE " + qualified(table), rs -> rs.getString(2));
            if (ddl == null) {
                continue;
            }
            List<ReferencingRow> referencing = referencing(target, table);
            objects.addAll(parse(table.getSchema(), table.getTable(), ddl, referencing, scope));
        }
        objects.sort(DeferredObject.BUILD_ORDER);
        return objects;
    }

    private static List<ReferencingRow> referencing(JdbcConnection target, TableId table) throws SQLException {
        return target.queryList(REFERENCING_SQL, rs -> new ReferencingRow(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                table.getSchema(), table.getTable());
    }

    /**
     * Pure translation of one {@code SHOW CREATE TABLE} text into deferred objects; kept apart
     * from the query so the parsing rules can be tested without a server.
     */
    static List<DeferredObject> parse(
            String schema, String table, String createTable, List<ReferencingRow> referencing, Set<String> scope) {
        boolean hasPrimaryKey = false;
        List<String> lines = new ArrayList<>();
        for (String raw : createTable.split("\n")) {
            String line = raw.trim();
            if (line.endsWith(",")) {
                line = line.substring(0, line.length() - 1).trim();
            }
            if (line.startsWith("PRIMARY KEY")) {
                hasPrimaryKey = true;
            }
            lines.add(line);
        }
        // Columns an outside foreign key points at: MySQL needs an index on them in the parent,
        // so the indexes leading with such a column are left alone.
        Set<String> pinnedColumns = new HashSet<>();
        for (ReferencingRow row : referencing) {
            if (!scope.contains(key(row.childSchema(), row.childTable())) && row.referencedColumn() != null) {
                pinnedColumns.add(row.referencedColumn().toLowerCase(Locale.ROOT));
            }
        }
        String qualifiedTable = quote(schema) + "." + quote(table);
        List<DeferredObject> objects = new ArrayList<>();
        int order = 0;
        for (String line : lines) {
            Matcher key = KEY_LINE.matcher(line);
            if (key.matches()) {
                order++;
                String kind = key.group(1);
                String name = unquote(key.group(2));
                DeferredObjectType type = "UNIQUE KEY".equals(kind) ? DeferredObjectType.UNIQUE : DeferredObjectType.INDEX;
                if (type == DeferredObjectType.UNIQUE && !hasPrimaryKey) {
                    continue;
                }
                Matcher first = FIRST_COLUMN.matcher(key.group(3));
                if (first.find() && pinnedColumns.contains(unquote(first.group(1)).toLowerCase(Locale.ROOT))) {
                    continue;
                }
                objects.add(new DeferredObject(schema, table, type, name,
                        "ALTER TABLE " + qualifiedTable + " ADD " + line,
                        "ALTER TABLE " + qualifiedTable + " DROP INDEX " + quote(name),
                        null, order));
                continue;
            }
            Matcher fk = FK_LINE.matcher(line);
            if (fk.matches()) {
                order++;
                String name = unquote(fk.group(1));
                objects.add(new DeferredObject(schema, table, DeferredObjectType.FOREIGN_KEY, name,
                        "ALTER TABLE " + qualifiedTable + " ADD " + qualifyReferences(line, schema),
                        "ALTER TABLE " + qualifiedTable + " DROP FOREIGN KEY " + quote(name),
                        null, order));
            }
        }
        return objects;
    }

    /**
     * {@code SHOW CREATE TABLE} leaves a same-database parent unqualified, which would resolve
     * against the session's default database instead of the child's. Qualify it.
     */
    static String qualifyReferences(String line, String schema) {
        Matcher matcher = REFERENCES.matcher(line);
        if (!matcher.find() || matcher.group(2) != null) {
            return line;
        }
        return line.substring(0, matcher.start(1)) + quote(schema) + "." + matcher.group(1)
                + line.substring(matcher.end(1));
    }

    @Override
    public List<ExternalReference> externalReferences(JdbcConnection target, Collection<TableId> tables)
            throws SQLException {
        Set<String> scope = scopeKeys(tables);
        List<ExternalReference> references = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TableId table : tables) {
            for (ReferencingRow row : referencing(target, table)) {
                if (scope.contains(key(row.childSchema(), row.childTable()))) {
                    continue;
                }
                if (seen.add(row.childSchema() + "." + row.childTable() + "." + row.constraintName())) {
                    references.add(new ExternalReference(row.childSchema(), row.childTable(),
                            row.constraintName(), table.getSchema(), table.getTable()));
                }
            }
        }
        return references;
    }

    @Override
    public boolean exists(JdbcConnection target, DeferredObject object) throws SQLException {
        String sql = object.type() == DeferredObjectType.FOREIGN_KEY ? FOREIGN_KEY_EXISTS_SQL : INDEX_EXISTS_SQL;
        return target.queryOne(sql, rs -> 1, object.schema(), object.table(), object.name()) != null;
    }

    @Override
    public List<String> disableForeignKeySql() {
        return List.of("SET FOREIGN_KEY_CHECKS = 0");
    }

    @Override
    public String truncateSql(TableId table) {
        return "TRUNCATE TABLE " + qualified(table);
    }

    @Override
    public String analyzeSql(TableId table) {
        return "ANALYZE TABLE " + qualified(table);
    }

    @Override
    public String probeRowSql(TableId table) {
        return "SELECT 1 FROM " + qualified(table) + " LIMIT 1";
    }

    /**
     * Folds the {@code ADD} clauses of several indexes of one table into one {@code ALTER TABLE},
     * so InnoDB sorts the table once. FULLTEXT and SPATIAL indexes are excluded: InnoDB builds
     * at most one FULLTEXT index per statement and neither may be combined online.
     */
    @Override
    public Optional<String> mergedCreate(List<DeferredObject> sameTable) {
        if (sameTable.size() < 2) {
            return Optional.empty();
        }
        DeferredObject first = sameTable.getFirst();
        String prefix = "ALTER TABLE " + quote(first.schema()) + "." + quote(first.table()) + " ";
        StringBuilder merged = new StringBuilder(prefix);
        for (int i = 0; i < sameTable.size(); i++) {
            DeferredObject object = sameTable.get(i);
            if (object.type() == DeferredObjectType.FOREIGN_KEY
                    || !object.schema().equals(first.schema())
                    || !object.table().equals(first.table())
                    || !object.createDdl().startsWith(prefix)) {
                return Optional.empty();
            }
            String clause = object.createDdl().substring(prefix.length());
            String upper = clause.toUpperCase(Locale.ROOT);
            if (upper.startsWith("ADD FULLTEXT") || upper.startsWith("ADD SPATIAL")) {
                return Optional.empty();
            }
            if (i > 0) {
                merged.append(", ");
            }
            merged.append(clause);
        }
        return Optional.of(merged.toString());
    }

    static String qualified(TableId table) {
        return quote(table.getSchema()) + "." + quote(table.getTable());
    }

    static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    static String unquote(String quoted) {
        String inner = quoted.substring(1, quoted.length() - 1);
        return inner.replace("``", "`");
    }

    static String key(String schema, String table) {
        return schema + "." + table;
    }

    static Set<String> scopeKeys(Collection<TableId> tables) {
        Set<String> keys = new HashSet<>();
        for (TableId table : tables) {
            keys.add(key(table.getSchema(), table.getTable()));
        }
        return keys;
    }
}
