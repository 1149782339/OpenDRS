package io.opendrs.jdbc.dialect;

import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import java.util.List;
import java.util.Properties;

public interface DbDialect {

    DbType type();

    String jdbcUrl(ConnectionInfo info);

    void afterConnect(JdbcConnection conn, ConnectionInfo info);

    default void applyConnectProperties(Properties props) {
    }

    String testSql();

    boolean schemaExists(JdbcConnection conn, String schema);

    boolean tableExists(JdbcConnection conn, String schema, String table);

    List<Table> listTables(JdbcConnection conn, String schema, List<TableRef> excludes);

    boolean hasSchemaPrivilege(JdbcConnection conn, String schema);
}
