package io.opendrs.jdbc.dialect;

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.migration.domain.DbType;
import java.util.EnumMap;
import java.util.Map;

public final class DbDialects {

    private static final Map<DbType, DbDialect> BY_TYPE = new EnumMap<>(DbType.class);

    static {
        BY_TYPE.put(DbType.MYSQL, new MysqlDialect());
        BY_TYPE.put(DbType.ORACLE, new OracleDialect());
        BY_TYPE.put(DbType.POSTGRESQL, new PostgresDialect());
    }

    private DbDialects() {
    }

    public static DbDialect of(DbType type) {
        DbDialect dialect = BY_TYPE.get(type);
        if (dialect == null) {
            throw AppException.of(ErrorCode.PARAM_INVALID, "Unsupported database type: " + type);
        }
        return dialect;
    }
}
