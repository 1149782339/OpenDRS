package io.opendrs.engine.precheck.support;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.JdbcException;
import io.opendrs.jdbc.RowMapper;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.Mockito;

/**
 * An in-memory stand-in for one MySQL or PostgreSQL endpoint.
 *
 * <p>The facts layer reads everything with SQL, so the only honest way to test the rules is to
 * answer that SQL. Each query is recognised by a distinctive fragment and served from the fields
 * below, which keeps the assertions about verdicts instead of about mock wiring.
 */
public final class FakeServer {

    public final DatabaseType type;
    public String version = "8.0.36";
    public String database = "hr";
    public String charset = "utf8mb4";
    public String collation = "utf8mb4_0900_ai_ci";
    public String timezoneName = "SYSTEM";
    public String systemTimezone = "CST";
    public int utcOffsetSeconds = 28_800;
    public boolean readOnly;
    public final Map<String, String> parameters = new LinkedHashMap<>();
    public final List<String> grants = new ArrayList<>();
    public final Map<String, String> engines = new LinkedHashMap<>();
    public final Set<String> primaryKeys = new LinkedHashSet<>();
    public final Set<String> existingTables = new LinkedHashSet<>();
    public final Set<String> schemas = new LinkedHashSet<>();
    /** Key {@code schema#PRIVILEGE}; absent means "not granted". */
    public final Map<String, Boolean> schemaPrivileges = new LinkedHashMap<>();
    public boolean databaseCreatePrivilege = true;
    /** Simulate a login without the privilege to set gtid_next (GtidNextPermissionCheck probe). */
    public boolean denyGtidNext;

    public FakeServer(DatabaseType type) {
        this.type = type;
    }

    public static FakeServer mysql() {
        FakeServer server = new FakeServer(DatabaseType.MYSQL);
        server.parameters.put("log_bin", "ON");
        server.parameters.put("binlog_format", "ROW");
        server.parameters.put("binlog_row_image", "FULL");
        server.parameters.put("log_slave_updates", "ON");
        server.parameters.put("binlog_expire_logs_seconds", "604800");
        server.parameters.put("gtid_mode", "ON");
        server.parameters.put("character_set_server", "utf8mb4");
        server.parameters.put("collation_server", "utf8mb4_0900_ai_ci");
        server.parameters.put("time_zone", "SYSTEM");
        server.parameters.put("system_time_zone", "CST");
        server.grants.add("GRANT SELECT, RELOAD, REPLICATION SLAVE, REPLICATION CLIENT, SHOW DATABASES"
                + " ON *.* TO `cdc`@`%`");
        return server;
    }

    public static FakeServer postgres() {
        FakeServer server = new FakeServer(DatabaseType.POSTGRESQL);
        server.version = "17.2";
        server.database = "appdb";
        server.charset = "UTF8";
        server.collation = "en_US.UTF-8";
        server.timezoneName = "Asia/Shanghai";
        server.parameters.put("server_encoding", "UTF8");
        server.parameters.put("lc_collate", "en_US.UTF-8");
        server.parameters.put("timezone", "Asia/Shanghai");
        server.schemas.add("public");
        server.schemaPrivileges.put("public#USAGE", true);
        server.schemaPrivileges.put("public#CREATE", true);
        return server;
    }

    public FakeServer table(String schema, String table, String engine, boolean primaryKey) {
        String key = schema + "." + table;
        engines.put(key, engine);
        existingTables.add(key);
        if (primaryKey) {
            primaryKeys.add(key);
        }
        return this;
    }

    public JdbcConnection connection() {
        return new Connection();
    }

    /** Routes {@code open} to the configured fake instead of a real driver. */
    public static JdbcConnectionFactory factory(Map<DatabaseType, FakeServer> servers) {
        return new JdbcConnectionFactory() {
            @Override
            public JdbcConnection open(JdbcConnectionConfig config) {
                FakeServer server = servers.get(config.getType());
                if (server == null) {
                    throw new JdbcException("Connection test failed: " + config.getType() + " "
                            + config.getHost() + ":" + config.getPort() + " — Connection refused");
                }
                return server.connection();
            }
        };
    }

    private final class Connection implements JdbcConnection {

        @Override
        public DatabaseType type() {
            return type;
        }

        @Override
        public void ping() {
        }

        @Override
        public java.sql.Connection unwrap() {
            try {
                java.sql.Connection raw = Mockito.mock(java.sql.Connection.class);
                DatabaseMetaData metadata = Mockito.mock(DatabaseMetaData.class);
                Mockito.when(raw.getMetaData()).thenReturn(metadata);
                Mockito.when(metadata.getDatabaseProductVersion()).thenReturn(version);
                return raw;
            } catch (SQLException ex) {
                throw new JdbcException(ex.getMessage(), ex);
            }
        }

        @Override
        public <T> T queryOne(String sql, RowMapper<T> mapper) {
            List<T> rows = queryList(sql, mapper);
            return rows.isEmpty() ? null : rows.getFirst();
        }

        @Override
        public <T> List<T> queryList(String sql, RowMapper<T> mapper) {
            return queryList(sql, mapper, new Object[0]);
        }

        @Override
        public <T> List<T> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return map(rowsFor(sql, params), mapper);
        }

        @Override
        public void execute(String sql) {
            if (denyGtidNext && sql.contains("gtid_next")) {
                throw new JdbcException("Access denied; you need (at least one of) the SUPER,"
                        + " SYSTEM_VARIABLES_ADMIN, SESSION_VARIABLES_ADMIN or REPLICATION_APPLIER"
                        + " privilege(s) for this operation");
            }
        }

        @Override
        public void close() {
        }
    }

    private List<Object[]> rowsFor(String sql, Object[] params) {
        String text = sql.toLowerCase(Locale.ROOT);
        if (text.startsWith("show variables") || text.startsWith("show all")) {
            return parameters.entrySet().stream()
                    .<Object[]>map(entry -> new Object[] {entry.getKey(), entry.getValue()})
                    .toList();
        }
        if (text.startsWith("show grants")) {
            return grants.stream().<Object[]>map(line -> new Object[] {line}).toList();
        }
        if (text.contains("information_schema.tables")) {
            return existingTables.stream()
                    .<Object[]>map(key -> new Object[] {schemaOf(key), tableOf(key), engines.get(key)})
                    .toList();
        }
        if (text.contains("information_schema.table_constraints")) {
            return primaryKeys.stream()
                    .<Object[]>map(key -> new Object[] {schemaOf(key), tableOf(key)})
                    .toList();
        }
        if (text.contains("pg_index")) {
            return primaryKeys.stream()
                    .<Object[]>map(key -> new Object[] {schemaOf(key), tableOf(key)})
                    .toList();
        }
        if (text.contains("pg_class")) {
            return existingTables.stream()
                    .<Object[]>map(key -> new Object[] {schemaOf(key), tableOf(key)})
                    .toList();
        }
        if (text.contains("pg_namespace where nspname")) {
            return List.<Object[]>of(new Object[] {existingSchema(String.valueOf(params[0]))});
        }
        if (text.contains("has_schema_privilege")) {
            String key = params[0] + "#" + params[1];
            return List.<Object[]>of(new Object[] {Boolean.TRUE.equals(schemaPrivileges.get(key))});
        }
        if (text.contains("has_database_privilege")) {
            return List.<Object[]>of(new Object[] {databaseCreatePrivilege});
        }
        if (text.contains("pg_is_in_recovery")) {
            return List.<Object[]>of(new Object[] {readOnly});
        }
        if (text.contains("extract(timezone") || text.contains("timestampdiff")) {
            return List.<Object[]>of(new Object[] {utcOffsetSeconds});
        }
        if (text.contains("@@global.read_only") || text.contains("@@global.super_read_only")) {
            return List.<Object[]>of(new Object[] {readOnly ? 1 : 0});
        }
        if (text.contains("current_database")) {
            return List.<Object[]>of(new Object[] {database});
        }
        return List.of();
    }

    private boolean existingSchema(String schema) {
        return schemas.stream().anyMatch(name -> name.equalsIgnoreCase(schema))
                || existingTables.stream().anyMatch(key -> schemaOf(key).equalsIgnoreCase(schema));
    }

    private static String schemaOf(String key) {
        int dot = key.indexOf('.');
        return dot < 0 ? key : key.substring(0, dot);
    }

    private static String tableOf(String key) {
        int dot = key.indexOf('.');
        return dot < 0 ? key : key.substring(dot + 1);
    }

    private static <T> List<T> map(List<Object[]> rows, RowMapper<T> mapper) {
        if (rows.isEmpty()) {
            return List.of();
        }
        ResultSet rs = Mockito.mock(ResultSet.class);
        AtomicInteger cursor = new AtomicInteger(-1);
        try {
            Mockito.when(rs.next()).thenAnswer(invocation -> cursor.incrementAndGet() < rows.size());
            Mockito.when(rs.getString(Mockito.anyInt())).thenAnswer(invocation -> {
                Object value = cell(rows, cursor, invocation.getArgument(0));
                return value == null ? null : String.valueOf(value);
            });
            Mockito.when(rs.getInt(Mockito.anyInt())).thenAnswer(invocation -> {
                Object value = cell(rows, cursor, invocation.getArgument(0));
                return value instanceof Number number ? number.intValue() : 0;
            });
            Mockito.when(rs.getBoolean(Mockito.anyInt())).thenAnswer(invocation -> {
                Object value = cell(rows, cursor, invocation.getArgument(0));
                return Boolean.TRUE.equals(value);
            });
            List<T> out = new ArrayList<>();
            while (rs.next()) {
                out.add(mapper.map(rs));
            }
            return out;
        } catch (SQLException ex) {
            throw new JdbcException(ex.getMessage(), ex);
        }
    }

    private static Object cell(List<Object[]> rows, AtomicInteger cursor, int column) {
        return rows.get(cursor.get())[column - 1];
    }
}
