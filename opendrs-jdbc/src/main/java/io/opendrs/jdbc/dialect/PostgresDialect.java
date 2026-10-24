package io.opendrs.jdbc.dialect;

import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.common.metadata.DatabaseType;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * PostgreSQL schema is a schema ({@code public}, …), not a database.
 * {@code JdbcConnectionConfig.database} is the database; {@code TableRef.schema} is the PG schema.
 *
 * <p>{@link #hasSchemaPrivilege} uses {@code has_schema_privilege(schema, 'CREATE')}. No DDL probes.
 */
public class PostgresDialect extends AbstractDbDialect {

    static final String SCHEMA_CREATE_SQL = "SELECT has_schema_privilege(?, 'CREATE')";

    /**
     * Mirrors Debezium's filter ({@code NOT LIKE 'pg_%' AND <> 'information_schema'}) and adds a
     * USAGE check: {@code pg_namespace} lists every schema regardless of grants, and a picker that
     * offers an unreadable schema is worse than one that omits it. The underscore is escaped so
     * only {@code pg_}-prefixed names are dropped.
     */
    static final String LIST_SCHEMAS_SQL = """
            SELECT nspname FROM pg_catalog.pg_namespace
             WHERE nspname NOT LIKE 'pg\\_%'
               AND nspname <> 'information_schema'
               AND has_schema_privilege(nspname, 'USAGE')
             ORDER BY nspname""";

    public PostgresDialect() {
        super(DatabaseType.POSTGRESQL);
    }

    @Override
    public List<String> listSchemas(JdbcConnection conn) {
        return List.copyOf(conn.queryList(LIST_SCHEMAS_SQL, rs -> rs.getString(1)));
    }

    /** Partitioned tables report their own JDBC type; without it they vanish from the picker. */
    @Override
    protected String[] tableTypes() {
        return new String[]{"TABLE", "PARTITIONED TABLE"};
    }

    @Override
    public void applyConnectProperties(Properties props) {
        props.setProperty("connectTimeout", "5");
        props.setProperty("loginTimeout", "5");
        // Lets the sink binder send Debezium logical/string values PostgreSQL can coerce.
        props.setProperty("stringtype", "unspecified");
    }

    /**
     * Unquoted PostgreSQL identifiers fold to lowercase.
     */
    @Override
    protected String normalize(String ident) {
        if (ident == null) {
            return null;
        }
        return ident.toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean hasSchemaPrivilege(JdbcConnection conn, String schema) {
        if (schema == null || schema.isBlank()) {
            return false;
        }
        try {
            List<Boolean> rows = conn.queryList(SCHEMA_CREATE_SQL, rs -> rs.getBoolean(1), schema);
            return rows != null && !rows.isEmpty() && Boolean.TRUE.equals(rows.getFirst());
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
