package io.opendrs.migration.mapper;

import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.engine.core.TaskState;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.engine.precheck.PrecheckResults;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MigrationTaskMapper {

    int insert(MigrationTask task);

    MigrationTask findById(@Param("id") Long id);

    MigrationTask findByName(@Param("name") String name);

    List<MigrationTask> findAll();

    /** Next auto-increment value of migration_task; see the mapper XML for the caveat. */
    Long selectNextAutoIncrement();

    List<MigrationTask> findDispatchable();

    int compareAndSetState(
            @Param("id") Long id,
            @Param("expected") TaskState expected,
            @Param("next") TaskState next);

    /** 暂停: CAS RUNNING → STOPPING(暂停中). Returns 0 when not RUNNING. */
    int markPausing(@Param("id") Long id);

    /** 结束: CAS RUNNING → TERMINATING(结束中). Returns 0 when not RUNNING. */
    int markTerminating(@Param("id") Long id);

    /**
     * 结束 on a task with nothing to wind down (state NULL/STARTING/STOPPED/FAILED):
     * straight to TERMINATED. Returns 0 when the row is elsewhere (e.g. RUNNING/STOPPING).
     */
    int terminateIdle(@Param("id") Long id);

    /** 结束 clicked mid-pause: escalate 暂停中(STOPPING) to 结束中(TERMINATING). */
    int flipStoppingToTerminating(@Param("id") Long id);

    /** Landing CAS: 暂停中 → 已暂停. */
    int landPausing(@Param("id") Long id);

    /** Landing CAS: 结束中 → 已结束. */
    int landTerminating(@Param("id") Long id);

    int compareAndSetPhase(
            @Param("id") Long id,
            @Param("expected") MigrationPhase expected,
            @Param("next") MigrationPhase next);

    int beginPrecheck(
            @Param("id") Long id,
            @Param("expectedPhase") MigrationPhase expectedPhase,
            @Param("expectedState") TaskState expectedState);

    int updatePrecheckResults(
            @Param("id") Long id, @Param("precheckResultsJson") PrecheckResults precheckResultsJson);

    int completePrecheckSuccess(@Param("id") Long id);

    int markPrecheckFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage);

    int updatePhase(@Param("id") Long id, @Param("phase") MigrationPhase phase);

    int updatePhaseState(
            @Param("id") Long id,
            @Param("phase") MigrationPhase phase,
            @Param("state") TaskState state);

    int markFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage);

    int markStoppingAsStopped();

    int deleteById(@Param("id") Long id);

    int countByConnectionId(@Param("connectionId") Long connectionId);
}
