package io.opendrs.migration.mapper.type;

import io.opendrs.migration.mapper.support.JacksonJsonTypeHandler;
import io.opendrs.precheck.PrecheckResults;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

@MappedTypes(PrecheckResults.class)
@MappedJdbcTypes({JdbcType.VARCHAR, JdbcType.OTHER})
public class PrecheckResultsTypeHandler extends JacksonJsonTypeHandler<PrecheckResults> {

    public PrecheckResultsTypeHandler() {
        super(PrecheckResults.class);
    }
}
