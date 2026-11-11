package io.opendrs.engine.jdbc;

import io.opendrs.engine.common.metadata.DatabaseType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * MySQL schema = database (JDBC URL database, {@code information_schema.SCHEMATA.SCHEMA_NAME},
 * and {@code db.table} are the same name).
 *
 * <p>{@link #hasSchemaPrivilege} reads {@code information_schema.USER_PRIVILEGES} (global {@code *.*})
 * and {@code SCHEMA_PRIVILEGES} (that database or {@code %}). No SHOW GRANTS parsing and no DDL probes.
 */
public class MySqlConnection extends JdbcConnection {

    static final String URL_PATTERN =
            "jdbc:mysql://${" + HOSTNAME + "}:${" + PORT + "}/${" + DATABASE + "}";

    private static final Field PORT_FIELD = Field.of(PORT, "3306");

    private static final ConnectionFactory FACTORY =
            patternBasedFactory(URL_PATTERN, MySqlConnection::connectProperties, PORT_FIELD);

    /**
     * GRANTEE in information_schema is {@code 'user'@'host'}; CURRENT_USER() is {@code user@host}.
     */
    static final String GRANTEE_SQL = "CONCAT('\\'', REPLACE(CURRENT_USER(), '@', '\\'@\\''), '\\'')";

    static final String GLOBAL_CREATE_SQL =
            "SELECT PRIVILEGE_TYPE FROM information_schema.USER_PRIVILEGES WHERE GRANTEE = "
                    + GRANTEE_SQL
                    + " AND PRIVILEGE_TYPE = 'CREATE'";

    static final String SCHEMA_CREATE_SQL =
            "SELECT PRIVILEGE_TYPE FROM information_schema.SCHEMA_PRIVILEGES WHERE GRANTEE = "
                    + GRANTEE_SQL
                    + " AND PRIVILEGE_TYPE = 'CREATE' AND (SCHEMA_NAME = ? OR SCHEMA_NAME = '%')";

    /**
     * Databases MySQL ships with, matching Debezium's {@code BUILT_IN_DB_NAMES}. Compared
     * case-insensitively: on Linux a database may be named {@code Sys}, and it is still a
     * system one.
     */
    static final Set<String> SYSTEM_SCHEMAS =
            Set.of("mysql", "performance_schema", "sys", "information_schema");

    public MySqlConnection(JdbcConnectionConfig config) {
        super(DatabaseType.MYSQL, config, FACTORY);
    }

    MySqlConnection(JdbcConnectionConfig config, ConnectionFactory factory) {
        super(DatabaseType.MYSQL, config, factory);
    }

    /**
     * Optional URL settings are sent as driver properties, which keeps {@link #URL_PATTERN} a
     * constant (the same split Debezium uses: template for the address, properties for the rest).
     */
    static Properties connectProperties(JdbcConnectionConfig config) {
        Properties props = JdbcConnection.properties(config);
        props.setProperty("connectTimeout", "5000");
        // MySQL TIMESTAMP has no offset on the wire. Connector/J otherwise interprets the
        // server's literal in the JVM time zone (connectionTimeZone=LOCAL), which shifts the
        // instant by the process/server offset — an Asia/Shanghai JVM against a UTC server
        // reads 2024-01-01 10:00 as 02:00Z. Pin the session and the driver to UTC so TIMESTAMP
        // reads are absolute instants, matching what PostgreSQL timestamptz returns.
        // An explicit endpoint serverTimezone still wins.
        if (config.extra("serverTimezone") == null) {
            props.setProperty("connectionTimeZone", "UTC");
            props.setProperty("forceConnectionTimeZoneToSession", "true");
        }
        // Sends a JDBC batch as multi-row INSERTs (or one multi-statement round trip for
        // UPDATE/DELETE). The applier never reads per-row update counts, which are the only
        // thing that changes (Statement.SUCCESS_NO_INFO). extra.rewriteBatchedStatements=false
        // switches it off per endpoint.
        props.setProperty("rewriteBatchedStatements", "true");
        copyBooleanProperty(config, "rewriteBatchedStatements", props, "rewriteBatchedStatements");
        copyBooleanProperty(config, "useSsl", props, "useSSL");
        copyProperty(config, "serverTimezone", props, "serverTimezone");
        copyBooleanProperty(config, "allowPublicKeyRetrieval", props, "allowPublicKeyRetrieval");
        return props;
    }

    /**
     * {@code SHOW DATABASES} rather than JDBC metadata: the server only lists databases the
     * connected user can see, and it is the same source Debezium reads.
     */
    @Override
    public List<String> listSchemas() throws SQLException {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : queryList("SHOW DATABASES", rs -> rs.getString(1))) {
            if (name != null && !name.isBlank() && !isSystemSchema(name)) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    @Override
    protected boolean isSystemSchema(String schema) {
        return schema != null && SYSTEM_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT));
    }

    @Override
    protected String catalog(String schema) {
        return schema;
    }

    @Override
    protected String schemaPattern(String schema) {
        return null;
    }

    @Override
    protected boolean searchCatalogs() {
        return true;
    }

    @Override
    protected String tableSchema(ResultSet rs) throws SQLException {
        String catalog = rs.getString("TABLE_CAT");
        if (catalog != null && !catalog.isBlank()) {
            return catalog;
        }
        return super.tableSchema(rs);
    }



    @Override
    public boolean hasSchemaPrivilege(String schema) throws SQLException {
        if (schema == null || schema.isBlank()) {
            return false;
        }
        try {
            if (queryOne(GLOBAL_CREATE_SQL, rs -> rs.getString(1)) != null) {
                return true;
            }
            List<String> schemaPrivileges =
                    queryList(SCHEMA_CREATE_SQL, rs -> rs.getString(1), schema);
            return schemaPrivileges != null && !schemaPrivileges.isEmpty();
        } catch (SQLException ex) {
            return false;
        }
    }
}
