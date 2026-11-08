package io.opendrs.migration.mapper.support;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public abstract class JacksonJsonTypeHandler<T> extends BaseTypeHandler<T> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Class<T> type;
    private final TypeReference<T> typeReference;

    protected JacksonJsonTypeHandler(Class<T> type) {
        this.type = type;
        this.typeReference = null;
    }

    protected JacksonJsonTypeHandler(TypeReference<T> typeReference) {
        this.type = null;
        this.typeReference = typeReference;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            ps.setString(i, MAPPER.writeValueAsString(parameter));
        } catch (JacksonException ex) {
            throw new SQLException("Failed to write JSON parameter", ex);
        }
    }

    @Override
    public T getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return read(rs.getString(columnName));
    }

    @Override
    public T getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return read(rs.getString(columnIndex));
    }

    @Override
    public T getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return read(cs.getString(columnIndex));
    }

    private T read(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node != null && node.isTextual()) {
                node = MAPPER.readTree(node.asString());
            }
            if (node == null || node.isNull()) {
                return null;
            }
            if (typeReference != null) {
                return MAPPER.convertValue(node, typeReference);
            }
            return MAPPER.convertValue(node, type);
        } catch (JacksonException ex) {
            throw new SQLException("Failed to read JSON column", ex);
        }
    }
}
