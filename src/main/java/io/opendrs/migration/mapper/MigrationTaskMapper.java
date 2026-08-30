package io.opendrs.migration.mapper;

import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.domain.TaskState;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MigrationTaskMapper {

    int insert(MigrationTask task);

    MigrationTask findById(@Param("id") Long id);

    MigrationTask findByName(@Param("name") String name);

    List<MigrationTask> findAll();

    int compareAndSetState(
            @Param("id") Long id,
            @Param("expected") TaskState expected,
            @Param("next") TaskState next);

    int updateState(@Param("id") Long id, @Param("state") TaskState state);

    int markFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage);

    int deleteById(@Param("id") Long id);

    int countByConnectionId(@Param("connectionId") Long connectionId);
}
