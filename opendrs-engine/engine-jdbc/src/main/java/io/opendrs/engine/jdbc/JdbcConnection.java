package io.opendrs.engine.jdbc;

import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.common.metadata.TableId;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * A JDBC connection with the database-generic query/metadata operations every engine shares.
 *
 * <p>The physical connection is supplied by a {@link ConnectionFactory} and opened lazily by
 * {@link #connection()}, so constructing a connection does not itself perform I/O and a subclass
 * can declare its factory as a static value before the superclass constructor runs.
 *
 * <p>Catalog/schema mapping is overridable ({@link #catalog}, {@link #schemaPattern},
 * {@link #normalize}):
 * <ul>
 *   <li>MYSQL: catalog = database/schema name, schema pattern {@code null}</li>
 *   <li>ORACLE: catalog {@code null}, schema = owner; unquoted identifiers uppercase</li>
 *   <li>POSTGRESQL: catalog {@code null}, schema = PG schema; unquoted identifiers lowercase</li>
 * </ul>
 */
public class JdbcConnection implements AutoCloseable {

    public static final String HOSTNAME = "hostname";
    public static final String PORT = "port";
    public static final String DATABASE = "database";
    public static final String USER = "user";
    public static final String PASSWORD = "password";

    /** Maps one result-set row to a value. */
    @FunctionalInterface
    public interface ResultSetExtractor<T> {
        T apply(ResultSet rs) throws SQLException;
    }

    private final DatabaseType type;
    private final JdbcConnectionConfig config;
    private final ConnectionFactory factory;
    private java.sql.Connection raw;

    public JdbcConnection(DatabaseType type, JdbcConnectionConfig config, ConnectionFactory factory) {
        this.type = type;
        this.config = config;
        this.factory = factory;
    }

    public final DatabaseType type() {
        return type;
    }

    public final JdbcConnectionConfig config() {
        return config;
    }

    /**
     * The open JDBC connection, opening it on first use. Also the entry point for callers that
     * need the raw {@link java.sql.Connection} (metadata APIs, third-party components).
     */
    public synchronized java.sql.Connection connection() throws SQLException {
        if (raw == null || raw.isClosed()) {
            java.sql.Connection opened = factory.connect(config);
            if (opened == null || opened.isClosed()) {
                throw new SQLException("Unable to obtain a JDBC connection");
            }
            try {
                opened.setAutoCommit(true);
                onConnection(opened);
            } catch (SQLException | RuntimeException ex) {
                closeQuietly(opened);
                throw ex;
            }
            raw = opened;
        }
        return raw;
    }

    /** Hook for engine-specific statements that must run once per opened connection. */
    protected void onConnection(java.sql.Connection connection) throws SQLException {
    }

    public void ping() throws SQLException {
        queryOne("SELECT 1", rs -> rs.getInt(1));
    }

    public <T> T queryOne(String sql, ResultSetExtractor<T> extractor) throws SQLException {
        List<T> rows = queryList(sql, extractor);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Single row with bound parameters; {@code null} when the query returns nothing. */
    public <T> T queryOne(String sql, ResultSetExtractor<T> extractor, Object... params)
            throws SQLException {
        List<T> rows = queryList(sql, extractor, params);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public <T> List<T> queryList(String sql, ResultSetExtractor<T> extractor) throws SQLException {
        return queryList(sql, extractor, new Object[0]);
    }

    public <T> List<T> queryList(String sql, ResultSetExtractor<T> extractor, Object... params)
            throws SQLException {
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    statement.setObject(i + 1, params[i]);
                }
            }
            try (ResultSet rs = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(extractor.apply(rs));
                }
                return rows;
            }
        }
    }

    public void execute(String sql) throws SQLException {
        try (Statement statement = connection().createStatement()) {
            statement.execute(sql);
        }
    }

    @Override
    public synchronized void close() {
        java.sql.Connection current = raw;
        raw = null;
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (SQLException ex) {
            throw new JdbcException(type + " — " + JdbcConnectionFactory.sanitize(ex.getMessage()), ex);
        }
    }

    // ---------------------------------------------------------------------
    // Metadata
    // ---------------------------------------------------------------------

    /**
     * JDBC table types to look for. PostgreSQL reports partitioned tables as their own type, so a
     * bare {@code TABLE} would silently drop them; that connection widens this.
     */
    protected String[] tableTypes() {
        return new String[]{"TABLE"};
    }

    public List<String> listSchemas() throws SQLException {
        Set<String> names = new TreeSet<>();
        DatabaseMetaData metadata = connection().getMetaData();
        try (ResultSet rs = metadata.getSchemas()) {
            while (rs.next()) {
                String name = schemaName(rs);
                if (name != null && !name.isBlank() && !isSystemSchema(name)) {
                    names.add(name);
                }
            }
        }
        if (searchCatalogs()) {
            try (ResultSet rs = metadata.getCatalogs()) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_CAT");
                    if (name != null && !name.isBlank() && !isSystemSchema(name)) {
                        names.add(name);
                    }
                }
            }
        }
        return List.copyOf(names);
    }

    /** System schemas never reach the picker. MYSQL: the four built-ins; POSTGRESQL: {@code pg_*}. */
    protected boolean isSystemSchema(String schema) {
        return false;
    }

    public boolean schemaExists(String schema) throws SQLException {
        if (schema == null || schema.isBlank()) {
            return false;
        }
        String normalized = normalize(schema);
        DatabaseMetaData metadata = connection().getMetaData();
        try (ResultSet rs = metadata.getSchemas(catalog(normalized), schemaPattern(normalized))) {
            while (rs.next()) {
                if (identEquals(normalized, schemaName(rs))) {
                    return true;
                }
            }
        }
        if (searchCatalogs()) {
            try (ResultSet rs = metadata.getCatalogs()) {
                while (rs.next()) {
                    if (identEquals(normalized, rs.getString("TABLE_CAT"))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean tableExists(String schema, String table) throws SQLException {
        if (schema == null || schema.isBlank() || table == null || table.isBlank()) {
            return false;
        }
        String normalizedSchema = normalize(schema);
        String normalizedTable = normalize(table);
        DatabaseMetaData metadata = connection().getMetaData();
        try (ResultSet rs = metadata.getTables(
                catalog(normalizedSchema),
                schemaPattern(normalizedSchema),
                normalizedTable,
                tableTypes())) {
            while (rs.next()) {
                if (identEquals(normalizedSchema, tableSchema(rs))
                        && identEquals(normalizedTable, rs.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<TableId> listTables(String schema, List<TableId> excludes) throws SQLException {
        if (schema == null || schema.isBlank()) {
            return List.of();
        }
        String normalizedSchema = normalize(schema);
        Set<TableId> excluded = new HashSet<>();
        if (excludes != null) {
            for (TableId exclude : excludes) {
                excluded.add(normalize(exclude));
            }
        }
        List<TableId> tables = new ArrayList<>();
        DatabaseMetaData metadata = connection().getMetaData();
        try (ResultSet rs = metadata.getTables(
                catalog(normalizedSchema),
                schemaPattern(normalizedSchema),
                "%",
                tableTypes())) {
            while (rs.next()) {
                String foundSchema = tableSchema(rs);
                String foundTable = rs.getString("TABLE_NAME");
                if (!identEquals(normalizedSchema, foundSchema) || foundTable == null) {
                    continue;
                }
                TableId id = TableId.of(foundSchema, foundTable);
                if (excluded.contains(normalize(id))) {
                    continue;
                }
                tables.add(id);
            }
        }
        return tables;
    }

    public boolean hasSchemaPrivilege(String schema) throws SQLException {
        throw new UnsupportedOperationException("hasSchemaPrivilege is not implemented for " + type);
    }

    /**
     * JDBC catalog for metadata lookups. Default is schema-oriented ({@code null}).
     * MYSQL: the database name.
     */
    protected String catalog(String schema) {
        return null;
    }

    /**
     * JDBC schema pattern for metadata lookups. Default is the schema name.
     * MYSQL: {@code null} (catalog holds the database).
     */
    protected String schemaPattern(String schema) {
        return schema;
    }

    /** Unquoted identifier fold. Default is as-written (MySQL). */
    protected String normalize(String ident) {
        return ident;
    }

    protected boolean identEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return expected.equals(actual);
    }

    /** MYSQL: also search {@code getCatalogs()} because schema === database. */
    protected boolean searchCatalogs() {
        return false;
    }

    protected String tableSchema(ResultSet rs) throws SQLException {
        String schema = rs.getString("TABLE_SCHEM");
        if (schema != null && !schema.isBlank()) {
            return schema;
        }
        return rs.getString("TABLE_CAT");
    }

    private TableId normalize(TableId id) {
        if (id == null) {
            return null;
        }
        return new TableId(id.getCatalog(), normalize(id.getSchema()), normalize(id.getTable()));
    }

    private static String schemaName(ResultSet rs) throws SQLException {
        String schema = rs.getString("TABLE_SCHEM");
        if (schema == null || schema.isBlank()) {
            return rs.getString("TABLE_CATALOG");
        }
        return schema;
    }

    // ---------------------------------------------------------------------
    // Connection factories
    // ---------------------------------------------------------------------

    /** DriverManager based factory with the default properties (user/password). */
    public static ConnectionFactory patternBasedFactory(String urlPattern, Field... variables) {
        return patternBasedFactory(urlPattern, JdbcConnection::properties, variables);
    }

    /** Factory that instantiates the given driver class with the given class loader. */
    public static ConnectionFactory patternBasedFactory(
            String urlPattern, String driverClassName, ClassLoader classLoader, Field... variables) {
        return patternBasedFactory(urlPattern, driverClassName, classLoader,
                JdbcConnection::properties, variables);
    }

    public static ConnectionFactory patternBasedFactory(
            String urlPattern,
            Function<JdbcConnectionConfig, Properties> properties,
            Field... variables) {
        return patternBasedFactory(urlPattern, null, null, properties, variables);
    }

    /**
     * Replaces {@code ${...}} variables in {@code urlPattern} and opens the connection.
     *
     * <p>The standard variables are {@code hostname}, {@code port}, {@code database}, {@code user}
     * and {@code password}; extra {@link Field}s may override any of them when the configuration
     * does not supply a value, which is how a connector pins its default port.
     */
    public static ConnectionFactory patternBasedFactory(
            String urlPattern,
            String driverClassName,
            ClassLoader classLoader,
            Function<JdbcConnectionConfig, Properties> properties,
            Field... variables) {
        return config -> {
            Properties props = properties.apply(config);
            String url = resolveUrl(urlPattern, config, variables);
            return open(url, props, driverClassName, classLoader);
        };
    }

    static String resolveUrl(String urlPattern, JdbcConnectionConfig config, Field... variables) {
        Map<String, String> values = new HashMap<>();
        values.put(HOSTNAME, config.getHost());
        values.put(PORT, java.lang.Integer.toString(config.getPort()));
        values.put(DATABASE, config.getDatabase());
        values.put(USER, config.getUsername());
        values.put(PASSWORD, config.getPassword());
        if (variables != null) {
            for (Field variable : variables) {
                if (variable == null) {
                    continue;
                }
                String current = values.get(variable.name());
                if (current == null || current.isEmpty()) {
                    values.put(variable.name(), variable.defaultValueAsString());
                }
            }
        }
        String url = urlPattern;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            url = url.replace("${" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
        }
        return url;
    }

    /** Default connection properties: the credentials every driver expects. */
    protected static Properties properties(JdbcConnectionConfig config) {
        Properties props = new Properties();
        props.setProperty(USER, config.getUsername() == null ? "" : config.getUsername());
        props.setProperty(PASSWORD, config.getPassword() == null ? "" : config.getPassword());
        return props;
    }

    static java.sql.Connection open(
            String url, Properties props, String driverClassName, ClassLoader classLoader)
            throws SQLException {
        if (driverClassName == null || driverClassName.isBlank()) {
            return DriverManager.getConnection(url, props);
        }
        try {
            ClassLoader loader = classLoader == null ? JdbcConnection.class.getClassLoader() : classLoader;
            Class<?> driverClass = Class.forName(driverClassName, true, loader);
            java.sql.Driver driver = (java.sql.Driver) driverClass.getDeclaredConstructor().newInstance();
            return driver.connect(url, props);
        } catch (ReflectiveOperationException ex) {
            throw new SQLException(ex);
        }
    }

    /** Copies a non-blank string extra into a driver property. */
    protected static void copyProperty(
            JdbcConnectionConfig config, String extraKey, Properties props, String propertyKey) {
        Object value = config.extra(extraKey);
        if (value != null && !java.lang.String.valueOf(value).isBlank()) {
            props.setProperty(propertyKey, java.lang.String.valueOf(value));
        }
    }

    /** Copies a boolean-ish extra ({@code true}/{@code 1}) into a driver property. */
    protected static void copyBooleanProperty(
            JdbcConnectionConfig config, String extraKey, Properties props, String propertyKey) {
        Object value = config.extra(extraKey);
        if (value != null) {
            props.setProperty(propertyKey, asBoolean(value));
        }
    }

    public static java.lang.String extraString(JdbcConnectionConfig config, String key) {
        Object value = config.extra(key);
        return value == null ? null : java.lang.String.valueOf(value);
    }

    private static java.lang.String asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        java.lang.String text = java.lang.String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        return ("true".equals(text) || "1".equals(text)) ? "true" : "false";
    }

    private static void closeQuietly(java.sql.Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // already failing with the original error
        }
    }
}
