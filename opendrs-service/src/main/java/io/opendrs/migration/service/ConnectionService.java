package io.opendrs.migration.service;

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.migration.api.request.CreateConnectionRequest;
import io.opendrs.migration.api.response.ConnectionResponse;
import io.opendrs.migration.api.response.ConnectionTestResponse;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConnectionService {

    private final ConnectionInfoMapper connectionMapper;
    private final MigrationTaskMapper taskMapper;
    private final JdbcConnectionFactory jdbcConnectionFactory;

    public ConnectionService(
            ConnectionInfoMapper connectionMapper,
            MigrationTaskMapper taskMapper,
            JdbcConnectionFactory jdbcConnectionFactory) {
        this.connectionMapper = connectionMapper;
        this.taskMapper = taskMapper;
        this.jdbcConnectionFactory = jdbcConnectionFactory;
    }

    @Transactional
    public ConnectionResponse create(CreateConnectionRequest request) {
        if (connectionMapper.findByName(request.name()) != null) {
            throw AppException.of(ErrorCode.PARAM_INVALID, "Connection name already exists: " + request.name());
        }
        ConnectionInfo row = toDomain(request.connection());
        row.setName(request.name());
        connectionMapper.insert(row);
        return toResponse(requireConnection(row.getId()));
    }

    @Transactional(readOnly = true)
    public List<ConnectionResponse> list() {
        return connectionMapper.findAll().stream().map(ConnectionService::toResponse).toList();
    }

    @Transactional
    public void delete(Long id) {
        requireConnection(id);
        if (taskMapper.countByConnectionId(id) > 0) {
            throw AppException.of(
                    ErrorCode.CONNECTION_IN_USE,
                    "Connection " + id + " is referenced by a migration task");
        }
        connectionMapper.deleteById(id);
    }

    public ConnectionTestResponse test(io.opendrs.migration.api.request.ConnectionInfo request) {
        return ping(toDomain(request));
    }

    public ConnectionTestResponse testById(Long id) {
        return ping(requireConnection(id));
    }

    private ConnectionTestResponse ping(ConnectionInfo info) {
        long started = System.nanoTime();
        try (JdbcConnection connection = jdbcConnectionFactory.open(info)) {
            connection.ping();
        }
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return new ConnectionTestResponse(true, latencyMs);
    }

    private ConnectionInfo requireConnection(Long id) {
        ConnectionInfo connection = connectionMapper.findById(id);
        if (connection == null) {
            throw AppException.of(ErrorCode.CONNECTION_NOT_FOUND, "Connection not found: " + id);
        }
        return connection;
    }

    private static ConnectionInfo toDomain(io.opendrs.migration.api.request.ConnectionInfo request) {
        ConnectionInfo connection = new ConnectionInfo();
        connection.setType(request.type());
        connection.setHost(request.host());
        connection.setPort(request.port());
        connection.setDbName(request.database());
        connection.setUsername(request.username());
        connection.setPassword(request.password());
        connection.setExtra(request.extra());
        return connection;
    }

    private static ConnectionResponse toResponse(ConnectionInfo connection) {
        return new ConnectionResponse(
                connection.getId(),
                connection.getName(),
                connection.getType(),
                connection.getHost(),
                connection.getPort(),
                connection.getDbName(),
                connection.getUsername(),
                "***",
                connection.getExtra(),
                connection.getCreatedAt(),
                connection.getUpdatedAt());
    }
}
