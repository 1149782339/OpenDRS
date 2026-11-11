package io.opendrs.migration.mapper;

import io.opendrs.migration.domain.DeferredObjectRecord;
import io.opendrs.migration.domain.DeferredObjectState;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DeferredObjectMapper {

    /** Idempotent: a row that already exists for the object identity is left alone. */
    int insertIgnore(DeferredObjectRecord row);

    List<DeferredObjectRecord> findByPipelineId(@Param("pipelineId") long pipelineId);

    List<DeferredObjectRecord> findByTaskId(@Param("taskId") long taskId);

    int updateState(
            @Param("id") long id,
            @Param("state") DeferredObjectState state,
            @Param("lastError") String lastError);

    /** One more rebuild attempt; {@code lastError} null on success. */
    int recordAttempt(
            @Param("id") long id,
            @Param("state") DeferredObjectState state,
            @Param("lastError") String lastError);

    /** FAILED → DROPPED with the attempt counter reset, so the restore tries them again. */
    int resetFailed(@Param("taskId") long taskId);

    int deleteByTaskId(@Param("taskId") long taskId);
}
