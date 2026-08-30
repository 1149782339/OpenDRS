package io.opendrs.jdbc.dialect;

import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.migration.domain.DbType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import org.springframework.stereotype.Component;

/**
 * MySQL schema = database (JDBC URL database, {@code information_schema.SCHEMATA.SCHEMA_NAME},
 * and {@code db.table} are the same name).
 *
 * <p>{@link #hasSchemaPrivilege} reads {@code information_schema.USER_PRIVILEGES} (global {@code *.*})
 * and {@code SCHEMA_PRIVILEGES} (that database or {@code %}). No SHOW GRANTS parsing and no DDL probes.
 */
@Component
public class MysqlDialect extends AbstractDbDialect {

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

    public MysqlDialect() {
        super(DbType.MYSQL);
    }

    @Override
    public void applyConnectProperties(Properties props) {
        props.setProperty("connectTimeout", "5000");
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
    public boolean hasSchemaPrivilege(JdbcConnection conn, String schema) {
        if (schema == null || schema.isBlank()) {
            return false;
        }
        try {
            if (conn.queryOne(GLOBAL_CREATE_SQL, rs -> rs.getString(1)) != null) {
                return true;
            }
            List<String> schemaPrivileges =
                    conn.queryList(SCHEMA_CREATE_SQL, rs -> rs.getString(1), schema);
            return schemaPrivileges != null && !schemaPrivileges.isEmpty();
        } catch (RuntimeException ex) {
            return false;
        }
    }
}
