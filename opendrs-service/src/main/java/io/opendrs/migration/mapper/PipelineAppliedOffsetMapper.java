package io.opendrs.migration.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface PipelineAppliedOffsetMapper {

    int deleteByTaskId(@Param("taskId") Long taskId);
}
