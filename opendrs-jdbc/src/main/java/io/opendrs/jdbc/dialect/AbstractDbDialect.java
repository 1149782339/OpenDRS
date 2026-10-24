package io.opendrs.jdbc.dialect;

import io.opendrs.jdbc.JdbcException;

import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.JdbcUrlBuilder;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.common.metadata.DatabaseType;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Generic {@link DatabaseMetaData} base for {@link DbDialect}. Catalog/schema mapping is
 * overridable ({@link #catalog}, {@link #schemaPattern}, {@link #normalize}):
 * <ul>
 *   <li>MYSQL: catalog = database/schema name, schema pattern {@code null}</li>
 *   <li>ORACLE: catalog {@code null}, schema = owner; unquoted identifiers uppercase</li>
 *   <li>POSTGRESQL: catalog {@code null}, schema = PG schema; unquoted identifiers lowercase</li>
 * </ul>
 * Existence and listing use {@code getSchemas}/{@code getTables} only.
 */
public abstract class AbstractDbDialect implements DbDialect {

    private final DatabaseType type;

    protected AbstractDbDialect(DatabaseType type) {
        this.type = type;
    }

    @Override
    public DatabaseType type() {
        return type;
    }

    @Override
    public String jdbcUrl(JdbcConnectionConfig config) {
        return JdbcUrlBuilder.url(config);
    }

    @Override
    public void afterConnect(JdbcConnection conn, JdbcConnectionConfig config) {
    }

    @Override
    public String testSql() {
        return "SELECT 1";
    }

    /**
     * JDBC table types to look for. PostgreSQL reports partitioned tables as their own type, so a
     * bare {@code TABLE} would silently drop them; that dialect widens this.
     */
    protected String[] tableTypes() {
        return new String[]{"TABLE"};
    }

    @Override
    public List<String> listSchemas(JdbcConnection conn) {
        Set<String> names = new TreeSet<>();
        try {
            DatabaseMetaData metadata = conn.unwrap().getMetaData();
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
        } catch (SQLException ex) {
            throw wrapMeta(ex);
        }
    }

    /** System schemas never reach the picker. MYSQL: the four built-ins; POSTGRESQL: {@code pg_*}. */
    protected boolean isSystemSchema(String schema) {
        return false;
    }

    @Override
    public boolean schemaExists(JdbcConnection conn, String schema) {
        if (schema == null || schema.isBlank()) {
            return false;
        }
        String normalized = normalize(schema);
        try {
            DatabaseMetaData metadata = conn.unwrap().getMetaData();
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
        } catch (SQLException ex) {
            throw wrapMeta(ex);
        }
    }

    @Override
    public boolean tableExists(JdbcConnection conn, String schema, String table) {
        if (schema == null || schema.isBlank() || table == null || table.isBlank()) {
            return false;
        }
        String normalizedSchema = normalize(schema);
        String normalizedTable = normalize(table);
        try {
            DatabaseMetaData metadata = conn.unwrap().getMetaData();
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
        } catch (SQLException ex) {
            throw wrapMeta(ex);
        }
    }

    @Override
    public List<Table> listTables(JdbcConnection conn, String schema, List<TableRef> excludes) {
        if (schema == null || schema.isBlank()) {
            return List.of();
        }
        String normalizedSchema = normalize(schema);
        Set<TableRef> excluded = new HashSet<>();
        if (excludes != null) {
            for (TableRef exclude : excludes) {
                excluded.add(normalize(exclude));
            }
        }
        List<Table> tables = new ArrayList<>();
        try {
            DatabaseMetaData metadata = conn.unwrap().getMetaData();
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
                    TableRef ref = new TableRef(foundSchema, foundTable);
                    if (excluded.contains(normalize(ref))) {
                        continue;
                    }
                    tables.add(new Table(ref));
                }
            }
            return tables;
        } catch (SQLException ex) {
            throw wrapMeta(ex);
        }
    }

    @Override
    public boolean hasSchemaPrivilege(JdbcConnection conn, String schema) {
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

    private TableRef normalize(TableRef ref) {
        if (ref == null) {
            return null;
        }
        return new TableRef(normalize(ref.schema()), normalize(ref.table()));
    }

    private static String schemaName(ResultSet rs) throws SQLException {
        String schema = rs.getString("TABLE_SCHEM");
        if (schema == null || schema.isBlank()) {
            return rs.getString("TABLE_CATALOG");
        }
        return schema;
    }

    private JdbcException wrapMeta(SQLException ex) {
        return new JdbcException(
                "Metadata lookup failed: " + type + " — " + JdbcConnectionFactory.sanitize(ex.getMessage()), ex);
    }
}
