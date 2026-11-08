package io.opendrs.migration.mapper;

import io.opendrs.migration.domain.TaskPipeline;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TaskPipelineMapper {

    int insert(TaskPipeline pipeline);

    TaskPipeline findById(@Param("id") Long id);

    List<TaskPipeline> findByTaskId(@Param("taskId") Long taskId);

    int deleteByTaskId(@Param("taskId") Long taskId);
}
