package io.opendrs.migration.mapper;

import io.opendrs.migration.domain.JobPhase;
import io.opendrs.migration.domain.JobState;
import io.opendrs.migration.domain.MigrationTask;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MigrationTaskMapper {

    int insert(MigrationTask task);

    MigrationTask findById(@Param("id") Long id);

    MigrationTask findByName(@Param("name") String name);

    List<MigrationTask> findAll();

    List<MigrationTask> findDispatchable();

    int compareAndSetJobState(
            @Param("id") Long id,
            @Param("expected") JobState expected,
            @Param("next") JobState next);

    int compareAndSetPhase(
            @Param("id") Long id,
            @Param("expected") JobPhase expected,
            @Param("next") JobPhase next);

    int beginPrecheck(
            @Param("id") Long id,
            @Param("expectedPhase") JobPhase expectedPhase,
            @Param("expectedJobState") JobState expectedJobState);

    int completePrecheckSuccess(@Param("id") Long id);

    int markPrecheckFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage);

    int updatePhase(@Param("id") Long id, @Param("phase") JobPhase phase);

    int updateJobControl(
            @Param("id") Long id,
            @Param("phase") JobPhase phase,
            @Param("jobState") JobState jobState);

    int markJobFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage);

    int markStoppingAsStopped();

    int deleteById(@Param("id") Long id);

    int countByConnectionId(@Param("connectionId") Long connectionId);
}
