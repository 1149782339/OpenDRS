package io.opendrs.jdbc.dialect;

import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.migration.domain.DbType;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL schema is a schema ({@code public}, …), not a database.
 * {@code ConnectionInfo.database} is the database; {@code TableRef.schema} is the PG schema.
 *
 * <p>{@link #hasSchemaPrivilege} uses {@code has_schema_privilege(schema, 'CREATE')}. No DDL probes.
 */
@Component
public class PostgresDialect extends AbstractDbDialect {

    static final String SCHEMA_CREATE_SQL = "SELECT has_schema_privilege(?, 'CREATE')";

    public PostgresDialect() {
        super(DbType.POSTGRESQL);
    }

    @Override
    public void applyConnectProperties(Properties props) {
        props.setProperty("connectTimeout", "5");
        props.setProperty("loginTimeout", "5");
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
