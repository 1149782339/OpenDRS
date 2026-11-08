package io.opendrs.engine.datacheck.type.handler;

import io.opendrs.engine.datacheck.type.LogicalValue;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.List;

public class PostgreSqlTypeHandler implements TypeHandler{
    @Override
    public List<LogicalValue> getValues(ResultSet resultSet, ResultSetMetaData metaData) {
        return List.of();
    }
}
