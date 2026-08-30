package io.opendrs.migration.mapper.type;

import io.opendrs.migration.api.request.TableSelection;
import io.opendrs.migration.mapper.support.JacksonJsonTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

@MappedTypes(TableSelection.class)
@MappedJdbcTypes({JdbcType.VARCHAR, JdbcType.OTHER})
public class TableSelectionTypeHandler extends JacksonJsonTypeHandler<TableSelection> {

    public TableSelectionTypeHandler() {
        super(TableSelection.class);
    }
}
