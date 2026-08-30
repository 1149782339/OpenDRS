package io.opendrs.migration.mapper;

import io.opendrs.migration.domain.DebeziumOffset;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DebeziumOffsetMapper {

    List<DebeziumOffset> findByTaskId(@Param("taskId") Long taskId);

    int deleteByTaskId(@Param("taskId") Long taskId);
}
