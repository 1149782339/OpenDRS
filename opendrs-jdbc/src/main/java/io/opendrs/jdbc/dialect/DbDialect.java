package io.opendrs.jdbc.dialect;

import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.common.metadata.DatabaseType;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public interface DbDialect {

    DatabaseType type();

    String jdbcUrl(JdbcConnectionConfig config);

    void afterConnect(JdbcConnection conn, JdbcConnectionConfig config);

    default void applyConnectProperties(Properties props) {
    }

    String testSql();

    boolean schemaExists(JdbcConnection conn, String schema);

    boolean tableExists(JdbcConnection conn, String schema, String table);

    /**
     * Every user-visible schema on the server, sorted by name. "Schema" is the neutral word for
     * {@code database} on MySQL (catalog) and {@code schema} on PostgreSQL; system schemas are
     * excluded here so callers never have to filter them.
     *
     * <p>Only schemas the connected user can actually read are returned: MySQL's
     * {@code SHOW DATABASES} hides the rest, and PostgreSQL is filtered through
     * {@code has_schema_privilege}.
     */
    List<String> listSchemas(JdbcConnection conn);

    List<Table> listTables(JdbcConnection conn, String schema, List<TableRef> excludes);

    boolean hasSchemaPrivilege(JdbcConnection conn, String schema);

}
