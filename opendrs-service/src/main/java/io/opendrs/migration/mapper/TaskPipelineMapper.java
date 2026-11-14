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

    /** Compare-and-set of {@code deferred_gate}; 0 when the gate was not {@code from}. */
    int compareAndSetDeferredGate(
            @Param("id") Long id,
            @Param("from") io.opendrs.migration.domain.DeferredGate from,
            @Param("to") io.opendrs.migration.domain.DeferredGate to);

    int updateSnapshotEndPosition(
            @Param("id") Long id,
            @Param("position") java.util.Map<String, Object> position);
}
