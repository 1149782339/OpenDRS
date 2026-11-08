package io.opendrs.engine.jdbc;

import io.opendrs.engine.common.metadata.DatabaseType;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * PostgreSQL schema is a schema ({@code public}, …), not a database.
 * {@code JdbcConnectionConfig.database} is the database; the {@code TableId} schema is the PG schema.
 *
 * <p>{@link #hasSchemaPrivilege} uses {@code has_schema_privilege(schema, 'CREATE')}. No DDL probes.
 */
public class PostgresConnection extends JdbcConnection {

    static final String URL_PATTERN =
            "jdbc:postgresql://${" + HOSTNAME + "}:${" + PORT + "}/${" + DATABASE + "}";

    private static final Field PORT_FIELD = Field.of(PORT, "5432");

    private static final ConnectionFactory FACTORY =
            patternBasedFactory(URL_PATTERN, PostgresConnection::connectProperties, PORT_FIELD);

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

    public PostgresConnection(JdbcConnectionConfig config) {
        super(DatabaseType.POSTGRESQL, config, FACTORY);
    }

    PostgresConnection(JdbcConnectionConfig config, ConnectionFactory factory) {
        super(DatabaseType.POSTGRESQL, config, factory);
    }

    /** Optional URL settings stay out of the pattern and become driver properties. */
    static Properties connectProperties(JdbcConnectionConfig config) {
        Properties props = JdbcConnection.properties(config);
        props.setProperty("connectTimeout", "5");
        props.setProperty("loginTimeout", "5");
        // Lets the sink binder send Debezium logical/string values PostgreSQL can coerce.
        props.setProperty("stringtype", "unspecified");
        copyProperty(config, "sslmode", props, "sslmode");
        copyBooleanProperty(config, "ssl", props, "ssl");
        copyProperty(config, "currentSchema", props, "currentSchema");
        return props;
    }

    @Override
    public List<String> listSchemas() throws SQLException {
        return List.copyOf(queryList(LIST_SCHEMAS_SQL, rs -> rs.getString(1)));
    }

    /** Partitioned tables report their own JDBC type; without it they vanish from the picker. */
    @Override
    protected String[] tableTypes() {
        return new String[]{"TABLE", "PARTITIONED TABLE"};
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
    public boolean hasSchemaPrivilege(String schema) throws SQLException {
        if (schema == null || schema.isBlank()) {
            return false;
        }
        try {
            List<Boolean> rows = queryList(SCHEMA_CREATE_SQL, rs -> rs.getBoolean(1), schema);
            return rows != null && !rows.isEmpty() && Boolean.TRUE.equals(rows.getFirst());
        } catch (SQLException ex) {
            return false;
        }
    }
}
