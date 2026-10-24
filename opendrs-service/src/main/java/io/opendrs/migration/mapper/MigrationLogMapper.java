package io.opendrs.migration.mapper;

import io.opendrs.common.log.MigrationLogLevel;
import io.opendrs.migration.domain.MigrationLog;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface MigrationLogMapper {

    /** One statement per batch: a snapshot can produce dozens of lines in a second. */
    int insertBatch(@Param("logs") List<MigrationLog> logs);

    /** Oldest first, so page 1 is the beginning of the run and the last page is the newest. */
    List<MigrationLog> findPage(
            @Param("taskId") Long taskId,
            @Param("level") MigrationLogLevel level,
            @Param("offset") int offset,
            @Param("limit") int limit);

    long count(@Param("taskId") Long taskId, @Param("level") MigrationLogLevel level);

    int deleteByTaskId(@Param("taskId") Long taskId);
}
