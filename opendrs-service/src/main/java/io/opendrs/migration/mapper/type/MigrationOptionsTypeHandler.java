package io.opendrs.migration.mapper.type;

import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.mapper.support.JacksonJsonTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

@MappedTypes(MigrationOptions.class)
@MappedJdbcTypes({JdbcType.VARCHAR, JdbcType.OTHER})
public class MigrationOptionsTypeHandler extends JacksonJsonTypeHandler<MigrationOptions> {

    public MigrationOptionsTypeHandler() {
        super(MigrationOptions.class);
    }
}
