package io.opendrs.migration.mapper.type;

import io.opendrs.migration.mapper.support.JacksonJsonTypeHandler;
import java.util.Map;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import tools.jackson.core.type.TypeReference;

@MappedJdbcTypes({JdbcType.VARCHAR, JdbcType.OTHER})
public class JsonMapTypeHandler extends JacksonJsonTypeHandler<Map<String, Object>> {

    public JsonMapTypeHandler() {
        super(new TypeReference<Map<String, Object>>() {
        });
    }
}
