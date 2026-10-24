package io.opendrs.jdbc.dialect;

import io.opendrs.jdbc.JdbcException;

import io.opendrs.common.metadata.DatabaseType;
import java.util.EnumMap;
import java.util.Map;

public final class DbDialects {

    private static final Map<DatabaseType, DbDialect> BY_TYPE = new EnumMap<>(DatabaseType.class);

    static {
        BY_TYPE.put(DatabaseType.MYSQL, new MysqlDialect());
        BY_TYPE.put(DatabaseType.ORACLE, new OracleDialect());
        BY_TYPE.put(DatabaseType.POSTGRESQL, new PostgresDialect());
    }

    private DbDialects() {
    }

    public static DbDialect of(DatabaseType type) {
        DbDialect dialect = BY_TYPE.get(type);
        if (dialect == null) {
            throw new JdbcException("Unsupported database type: " + type);
        }
        return dialect;
    }
}
