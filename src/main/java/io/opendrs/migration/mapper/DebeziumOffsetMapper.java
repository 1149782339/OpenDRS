package io.opendrs.migration.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DebeziumOffsetMapper {

    int deleteByTaskId(@Param("taskId") Long taskId);
}
