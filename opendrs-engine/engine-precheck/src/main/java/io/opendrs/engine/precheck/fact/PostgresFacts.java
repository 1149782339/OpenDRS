package io.opendrs.engine.precheck.fact;

import io.opendrs.engine.precheck.Side;
import io.opendrs.engine.jdbc.JdbcConnection;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import io.opendrs.engine.jdbc.JdbcConnectionFactory;
import io.opendrs.engine.common.metadata.TableId;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PostgreSQL facts. Settings come from one {@code SHOW ALL}; schema privileges are answered by
 * {@code has_schema_privilege} rather than by parsing {@code aclitem} arrays, which is both shorter
 * and immune to role inheritance subtleties.
 */
public final class PostgresFacts extends EndpointFacts {

    public PostgresFacts(Side side, JdbcConnectionConfig config, JdbcConnectionFactory factory) {
        super(side, config, factory);
    }

    @Override
    protected Map<String, String> loadParameters(JdbcConnection conn) throws SQLException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String[] row : conn.queryList(
                "SHOW ALL", rs -> new String[] {rs.getString(1), rs.getString(2)})) {
            if (row[0] != null) {
                out.put(row[0].toLowerCase(Locale.ROOT), row[1]);
            }
        }
        return out;
    }

    @Override
    protected CharsetInfo loadCharset(JdbcConnection conn) throws SQLException {
        return new CharsetInfo(parameters().get("server_encoding"), parameters().get("lc_collate"));
    }

    @Override
    protected TimezoneInfo loadTimezone(JdbcConnection conn) throws SQLException {
        String zone = parameters().get("timezone");
        Integer offset = conn.queryOne(
                "SELECT CAST(EXTRACT(TIMEZONE FROM now()) AS int)", rs -> rs.getInt(1));
        return new TimezoneInfo(zone, offset == null ? 0 : offset);
    }

    @Override
    protected InstanceState loadInstanceState(JdbcConnection conn) throws SQLException {
        Boolean inRecovery = conn.queryOne("SELECT pg_is_in_recovery()", rs -> rs.getBoolean(1));
        boolean recovery = Boolean.TRUE.equals(inRecovery);
        return new InstanceState(!recovery, recovery ? "pg_is_in_recovery=true（只读备库）" : "pg_is_in_recovery=false");
    }

    /**
     * A target schema usually does not exist yet - schema migration creates it - so CREATE is judged
     * on the database, which is what actually allows {@code CREATE SCHEMA}.
     */
    @Override
    protected List<String> loadMissingPermissions(
            JdbcConnection conn, Collection<String> schemas, List<String> required)
            throws SQLException {
        List<String> missing = new ArrayList<>();
        String database = conn.queryOne("SELECT current_database()", rs -> rs.getString(1));
        for (String schema : schemas) {
            Boolean exists = conn.queryOne(
                    "SELECT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = ?)",
                    rs -> rs.getBoolean(1),
                    schema);
            if (!Boolean.TRUE.equals(exists)) {
                if (containsIgnoreCase(required, "CREATE")) {
                    Boolean dbCreate = conn.queryOne(
                            "SELECT has_database_privilege(current_user, current_database(), 'CREATE')",
                            rs -> rs.getBoolean(1));
                    if (!Boolean.TRUE.equals(dbCreate)) {
                        missing.add("CREATE（数据库 " + database + " 上，用于创建 schema " + schema + "）");
                    }
                }
                continue;
            }
            for (String privilege : required) {
                Boolean granted = conn.queryOne(
                        "SELECT has_schema_privilege(?::name, ?::text)",
                        rs -> rs.getBoolean(1),
                        schema,
                        privilege);
                if (!Boolean.TRUE.equals(granted)) {
                    missing.add(privilege + " ON SCHEMA " + schema);
                }
            }
        }
        return List.copyOf(missing);
    }

    @Override
    protected void loadTableFacts(
            JdbcConnection conn, Collection<TableId> tables, Map<TableId, TableFacts> out)
            throws SQLException {
        if (tables.isEmpty()) {
            return;
        }
        List<String> schemas = distinctSchemas(tables);
        String placeholders = String.join(",", java.util.Collections.nCopies(schemas.size(), "?"));
        Object[] params = schemas.toArray();

        Set<String> existing = new HashSet<>();
        conn.queryList(
                        "SELECT n.nspname, c.relname FROM pg_class c"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " WHERE c.relkind IN ('r', 'p') AND n.nspname IN (" + placeholders + ")",
                        rs -> pgKey(rs.getString(1), rs.getString(2)),
                        params)
                .forEach(existing::add);

        Set<String> withPrimaryKey = new HashSet<>();
        conn.queryList(
                        "SELECT n.nspname, c.relname FROM pg_class c"
                                + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                                + " JOIN pg_index i ON i.indrelid = c.oid AND i.indisprimary"
                                + " WHERE c.relkind IN ('r', 'p') AND n.nspname IN (" + placeholders + ")",
                        rs -> pgKey(rs.getString(1), rs.getString(2)),
                        params)
                .forEach(withPrimaryKey::add);

        for (TableId ref : tables) {
            String tableKey = pgKey(ref.getSchema(), ref.getTable());
            if (!existing.contains(tableKey)) {
                continue;
            }
            out.put(ref, new TableFacts(ref, true, null, withPrimaryKey.contains(tableKey)));
        }
    }

    /** Unquoted PostgreSQL identifiers fold to lower case, matching the dialect's metadata lookups. */
    private static String pgKey(String schema, String table) {
        return (schema == null ? "" : schema.toLowerCase(Locale.ROOT))
                + "." + (table == null ? "" : table.toLowerCase(Locale.ROOT));
    }

    private static List<String> distinctSchemas(Collection<TableId> tables) {
        Set<String> schemas = new LinkedHashSet<>();
        for (TableId id : tables) {
            if (id.getSchema() != null && !id.getSchema().isBlank()) {
                schemas.add(id.getSchema());
            }
        }
        return List.copyOf(schemas);
    }

    private static boolean containsIgnoreCase(List<String> values, String wanted) {
        return values.stream().anyMatch(value -> value.equalsIgnoreCase(wanted));
    }
}
