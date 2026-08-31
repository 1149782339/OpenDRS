package io.opendrs.migration.mapper;

import io.opendrs.migration.domain.ConnectionInfo;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConnectionInfoMapper {

    int insert(ConnectionInfo connection);

    List<ConnectionInfo> findAll();

    ConnectionInfo findById(@Param("id") Long id);

    ConnectionInfo findByName(@Param("name") String name);

    int deleteById(@Param("id") Long id);
}
