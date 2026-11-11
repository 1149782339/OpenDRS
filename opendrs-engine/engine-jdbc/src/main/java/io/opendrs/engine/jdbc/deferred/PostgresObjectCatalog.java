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
import java.util.Set;

/**
 * PostgreSQL catalog reader: DDL comes from {@code pg_get_indexdef} / {@code pg_get_constraintdef},
 * so expression indexes, partial indexes, operator classes, {@code INCLUDE} columns and
 * {@code NULLS NOT DISTINCT} come back exactly as they were.
 *
 * <p>Foreign keys are re-added {@code NOT VALID} (instant, no scan, no long lock) and validated
 * by a separate {@code VALIDATE CONSTRAINT}, which only takes a {@code SHARE UPDATE EXCLUSIVE}
 * lock and therefore does not block replay.
 */
public final class PostgresObjectCatalog implements TargetObjectCatalog {

    static final String HAS_PRIMARY_KEY_SQL = """
            SELECT 1 FROM pg_index ix
              JOIN pg_class t ON t.oid = ix.indrelid
              JOIN pg_namespace n ON n.oid = t.relnamespace
             WHERE n.nspname = ? AND t.relname = ? AND ix.indisprimary""";

    /**
     * Secondary indexes of one table. Skips the primary key, invalid leftovers of a failed
     * {@code CREATE INDEX CONCURRENTLY}, indexes attached to a partitioned parent's index
     * ({@code pg_inherits}: the parent's index owns them) and exclusion constraints.
     */
    static final String INDEXES_SQL = """
            SELECT i.relname AS index_name,
                   ix.indexrelid AS index_oid,
                   ix.indisunique AS is_unique,
                   pg_get_indexdef(ix.indexrelid) AS index_def,
                   c.conname AS constraint_name,
                   c.contype AS constraint_type,
                   CASE WHEN c.oid IS NULL THEN NULL ELSE pg_get_constraintdef(c.oid) END AS constraint_def
              FROM pg_index ix
              JOIN pg_class i ON i.oid = ix.indexrelid
              JOIN pg_class t ON t.oid = ix.indrelid
              JOIN pg_namespace n ON n.oid = t.relnamespace
              LEFT JOIN pg_constraint c
                ON c.conindid = ix.indexrelid AND c.conrelid = t.oid AND c.contype IN ('p', 'u', 'x')
             WHERE n.nspname = ? AND t.relname = ?
               AND NOT ix.indisprimary
               AND ix.indisvalid
               AND NOT EXISTS (SELECT 1 FROM pg_inherits h WHERE h.inhrelid = ix.indexrelid)
             ORDER BY i.relname""";

    /** Foreign keys declared on one table; partition clones ({@code conparentid <> 0}) are skipped. */
    static final String FOREIGN_KEYS_SQL = """
            SELECT c.conname AS constraint_name, pg_get_constraintdef(c.oid) AS constraint_def
              FROM pg_constraint c
              JOIN pg_class t ON t.oid = c.conrelid
              JOIN pg_namespace n ON n.oid = t.relnamespace
             WHERE c.contype = 'f' AND n.nspname = ? AND t.relname = ? AND c.conparentid = 0
             ORDER BY c.conname""";

    /** Foreign keys (from anywhere) that reference one table, with the parent index they use. */
    static final String REFERENCING_SQL = """
            SELECT fn.nspname AS child_schema, ft.relname AS child_table, f.conname AS constraint_name,
                   f.conindid AS index_oid
              FROM pg_constraint f
              JOIN pg_class ft ON ft.oid = f.conrelid
              JOIN pg_namespace fn ON fn.oid = ft.relnamespace
              JOIN pg_class pt ON pt.oid = f.confrelid
              JOIN pg_namespace pn ON pn.oid = pt.relnamespace
             WHERE f.contype = 'f' AND f.conparentid = 0 AND pn.nspname = ? AND pt.relname = ?""";

    static final String INDEX_EXISTS_SQL = """
            SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('i', 'I')""";

    static final String CONSTRAINT_EXISTS_SQL = """
            SELECT 1 FROM pg_constraint c
              JOIN pg_class t ON t.oid = c.conrelid
              JOIN pg_namespace n ON n.oid = t.relnamespace
             WHERE n.nspname = ? AND t.relname = ? AND c.conname = ?""";

    @Override
    public DatabaseType type() {
        return DatabaseType.POSTGRESQL;
    }

    /** One index row as read from {@link #INDEXES_SQL}. */
    record IndexRow(
            String indexName,
            long indexOid,
            boolean unique,
            String indexDef,
            String constraintName,
            String constraintType,
            String constraintDef) {
    }

    /** One foreign key that references an in-scope table. */
    record ReferencingRow(String childSchema, String childTable, String constraintName, long indexOid) {
    }

    @Override
    public List<DeferredObject> capture(JdbcConnection target, Collection<TableId> tables) throws SQLException {
        Set<String> scope = scopeKeys(tables);
        List<DeferredObject> objects = new ArrayList<>();
        for (TableId table : tables) {
            String schema = table.getSchema();
            String name = table.getTable();
            boolean hasPrimaryKey = target.queryOne(HAS_PRIMARY_KEY_SQL, rs -> 1, schema, name) != null;
            List<IndexRow> indexes = target.queryList(INDEXES_SQL, rs -> new IndexRow(
                    rs.getString("index_name"),
                    rs.getLong("index_oid"),
                    rs.getBoolean("is_unique"),
                    rs.getString("index_def"),
                    rs.getString("constraint_name"),
                    rs.getString("constraint_type"),
                    rs.getString("constraint_def")), schema, name);
            List<ReferencingRow> referencing = target.queryList(REFERENCING_SQL, rs -> new ReferencingRow(
                    rs.getString("child_schema"),
                    rs.getString("child_table"),
                    rs.getString("constraint_name"),
                    rs.getLong("index_oid")), schema, name);
            List<String[]> foreignKeys = target.queryList(FOREIGN_KEYS_SQL, rs -> new String[]{
                    rs.getString("constraint_name"), rs.getString("constraint_def")}, schema, name);
            objects.addAll(toObjects(schema, name, hasPrimaryKey, indexes, referencing, foreignKeys, scope));
        }
        objects.sort(DeferredObject.BUILD_ORDER);
        return objects;
    }

    /**
     * Pure translation of the catalog rows of one table into deferred objects; kept apart from
     * the queries so the selection rules can be tested without a server.
     */
    static List<DeferredObject> toObjects(
            String schema,
            String table,
            boolean hasPrimaryKey,
            List<IndexRow> indexes,
            List<ReferencingRow> referencing,
            List<String[]> foreignKeys,
            Set<String> scope) {
        // Parent indexes an outside foreign key depends on: dropping them would fail (or need
        // CASCADE, which would drop the outsider's constraint), so they stay.
        Set<Long> pinnedByOutsiders = new HashSet<>();
        for (ReferencingRow row : referencing) {
            if (!scope.contains(key(row.childSchema(), row.childTable()))) {
                pinnedByOutsiders.add(row.indexOid());
            }
        }
        List<DeferredObject> objects = new ArrayList<>();
        int order = 0;
        String qualifiedTable = quote(schema) + "." + quote(table);
        for (IndexRow row : indexes) {
            order++;
            if ("p".equals(row.constraintType()) || "x".equals(row.constraintType())) {
                continue;
            }
            if (row.unique()) {
                if (!hasPrimaryKey || pinnedByOutsiders.contains(row.indexOid())) {
                    continue;
                }
                if ("u".equals(row.constraintType()) && row.constraintName() != null) {
                    objects.add(new DeferredObject(schema, table, DeferredObjectType.UNIQUE, row.constraintName(),
                            "ALTER TABLE " + qualifiedTable + " ADD CONSTRAINT " + quote(row.constraintName())
                                    + " " + row.constraintDef(),
                            "ALTER TABLE " + qualifiedTable + " DROP CONSTRAINT " + quote(row.constraintName()),
                            null, order));
                } else {
                    objects.add(new DeferredObject(schema, table, DeferredObjectType.UNIQUE, row.indexName(),
                            row.indexDef(),
                            "DROP INDEX " + quote(schema) + "." + quote(row.indexName()),
                            null, order));
                }
                continue;
            }
            objects.add(new DeferredObject(schema, table, DeferredObjectType.INDEX, row.indexName(),
                    row.indexDef(),
                    "DROP INDEX " + quote(schema) + "." + quote(row.indexName()),
                    null, order));
        }
        int fkOrder = 0;
        for (String[] fk : foreignKeys) {
            fkOrder++;
            String constraintName = fk[0];
            String definition = stripNotValid(fk[1]);
            objects.add(new DeferredObject(schema, table, DeferredObjectType.FOREIGN_KEY, constraintName,
                    "ALTER TABLE " + qualifiedTable + " ADD CONSTRAINT " + quote(constraintName) + " "
                            + definition + " NOT VALID",
                    "ALTER TABLE " + qualifiedTable + " DROP CONSTRAINT " + quote(constraintName),
                    fk[1].trim().toUpperCase(Locale.ROOT).endsWith("NOT VALID")
                            // The source constraint was never validated; keep it that way.
                            ? null
                            : "ALTER TABLE " + qualifiedTable + " VALIDATE CONSTRAINT " + quote(constraintName),
                    fkOrder));
        }
        return objects;
    }

    static String stripNotValid(String definition) {
        String trimmed = definition.trim();
        if (trimmed.toUpperCase(Locale.ROOT).endsWith("NOT VALID")) {
            return trimmed.substring(0, trimmed.length() - "NOT VALID".length()).trim();
        }
        return trimmed;
    }

    @Override
    public List<ExternalReference> externalReferences(JdbcConnection target, Collection<TableId> tables)
            throws SQLException {
        Set<String> scope = scopeKeys(tables);
        List<ExternalReference> references = new ArrayList<>();
        for (TableId table : tables) {
            for (ReferencingRow row : target.queryList(REFERENCING_SQL, rs -> new ReferencingRow(
                    rs.getString("child_schema"),
                    rs.getString("child_table"),
                    rs.getString("constraint_name"),
                    rs.getLong("index_oid")), table.getSchema(), table.getTable())) {
                if (!scope.contains(key(row.childSchema(), row.childTable()))) {
                    references.add(new ExternalReference(row.childSchema(), row.childTable(),
                            row.constraintName(), table.getSchema(), table.getTable()));
                }
            }
        }
        return references;
    }

    @Override
    public boolean exists(JdbcConnection target, DeferredObject object) throws SQLException {
        if (object.type() == DeferredObjectType.FOREIGN_KEY) {
            return target.queryOne(CONSTRAINT_EXISTS_SQL, rs -> 1,
                    object.schema(), object.table(), object.name()) != null;
        }
        // A unique constraint is backed by an index of the same name, so one probe covers both.
        return target.queryOne(INDEX_EXISTS_SQL, rs -> 1, object.schema(), object.name()) != null;
    }

    @Override
    public List<String> disableForeignKeySql() {
        // Foreign keys are RI triggers in PostgreSQL; replica mode skips them (and user triggers).
        return List.of("SET session_replication_role = replica");
    }

    @Override
    public String truncateSql(TableId table) {
        return "TRUNCATE TABLE " + qualified(table);
    }

    @Override
    public String analyzeSql(TableId table) {
        return "ANALYZE " + qualified(table);
    }

    @Override
    public String probeRowSql(TableId table) {
        return "SELECT 1 FROM " + qualified(table) + " LIMIT 1";
    }

    static String qualified(TableId table) {
        return quote(table.getSchema()) + "." + quote(table.getTable());
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
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
