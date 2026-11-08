package io.opendrs.migration.mapper;

import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FullLoadCheckpointMapper {

    /**
     * Records a finished table; a repeated completion (re-export after a resume) overwrites the
     * row count, the original {@code created_at} is kept.
     */
    int insertCompleted(
            @Param("pipelineId") long pipelineId,
            @Param("table") String table,
            @Param("rowCount") long rowCount);

    /** Tables whose full load committed on the target; empty (never null) when none. */
    Set<String> findDoneTables(@Param("pipelineId") long pipelineId);

    /**
     * Task-level delete via subquery on {@code task_pipelines}, in the same explicit-cleanup
     * style as the offset/schema-history tables (which also key by pipeline); pipeline rows
     * themselves are deleted afterwards by the caller.
     */
    int deleteByTaskId(@Param("taskId") long taskId);
}
