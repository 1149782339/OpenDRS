package io.opendrs.migration.service;

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.migration.api.request.CreateMigrationTaskRequest;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.api.response.MigrationStatusResponse;
import io.opendrs.migration.api.response.MigrationTaskResponse;
import io.opendrs.migration.api.response.MigrationTaskSummary;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.domain.TaskState;
import io.opendrs.migration.job.TaskJobRegistry;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.DebeziumOffsetMapper;
import io.opendrs.migration.mapper.DebeziumSchemaHistoryMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MigrationTaskService {

    private final MigrationTaskMapper taskMapper;
    private final ConnectionInfoMapper connectionMapper;
    private final DebeziumOffsetMapper offsetMapper;
    private final DebeziumSchemaHistoryMapper schemaHistoryMapper;
    private final MappingValidator mappingValidator;
    private final TaskJobRegistry jobRegistry;
    private final TransactionTemplate transactionTemplate;

    public MigrationTaskService(
            MigrationTaskMapper taskMapper,
            ConnectionInfoMapper connectionMapper,
            DebeziumOffsetMapper offsetMapper,
            DebeziumSchemaHistoryMapper schemaHistoryMapper,
            MappingValidator mappingValidator,
            TaskJobRegistry jobRegistry,
            TransactionTemplate transactionTemplate) {
        this.taskMapper = taskMapper;
        this.connectionMapper = connectionMapper;
        this.offsetMapper = offsetMapper;
        this.schemaHistoryMapper = schemaHistoryMapper;
        this.mappingValidator = mappingValidator;
        this.jobRegistry = jobRegistry;
        this.transactionTemplate = transactionTemplate;
    }

    @Transactional
    public MigrationTaskResponse create(CreateMigrationTaskRequest request) {
        mappingValidator.validate(request.tables());
        if (taskMapper.findByName(request.name()) != null) {
            throw AppException.of(ErrorCode.TASK_CONFLICT, "Task name already exists: " + request.name());
        }

        MigrationOptions options = request.options() == null
                ? MigrationOptions.defaults()
                : request.options().withDefaults();

        ConnectionInfo source = insertConnection(request.name() + "-source", request.source());
        ConnectionInfo target = insertConnection(request.name() + "-target", request.target());

        MigrationTask task = new MigrationTask();
        task.setName(request.name());
        task.setMode(request.mode());
        task.setState(TaskState.CREATED);
        task.setSourceConnectionId(source.getId());
        task.setTargetConnectionId(target.getId());
        task.setTablesJson(request.tables());
        task.setOptionsJson(options);
        taskMapper.insert(task);
        return toResponse(requireTask(task.getId()), source, target);
    }

    @Transactional(readOnly = true)
    public List<MigrationTaskSummary> list() {
        return taskMapper.findAll().stream().map(this::toSummary).toList();
    }

    @Transactional(readOnly = true)
    public MigrationTaskResponse get(Long id) {
        MigrationTask task = requireTask(id);
        return toResponse(task, requireConnection(task.getSourceConnectionId()),
                requireConnection(task.getTargetConnectionId()));
    }

    @Transactional(readOnly = true)
    public MigrationStatusResponse status(Long id) {
        return toStatus(requireTask(id));
    }

    public MigrationStatusResponse start(Long id) {
        MigrationTask prepared = transactionTemplate.execute(status -> {
            MigrationTask task = requireTask(id);
            if (task.getState() != TaskState.CREATED
                    && task.getState() != TaskState.STOPPED
                    && task.getState() != TaskState.FAILED) {
                throw AppException.of(
                        ErrorCode.TASK_CONFLICT,
                        "Task " + id + " cannot be started from state " + task.getState());
            }
            int updated = taskMapper.compareAndSetState(id, task.getState(), TaskState.STARTING);
            if (updated == 0) {
                throw AppException.of(
                        ErrorCode.TASK_CONFLICT,
                        "Task " + id + " cannot be started from state " + task.getState());
            }
            task.setState(TaskState.STARTING);
            task.setErrorMessage(null);
            return task;
        });
        jobRegistry.start(prepared);
        return toStatus(requireTask(id));
    }

    public MigrationStatusResponse stop(Long id) {
        requireTask(id);
        throw AppException.of(
                ErrorCode.TASK_CONFLICT,
                "Stop is not implemented; task " + id + " cannot be stopped in v1");
    }

    @Transactional
    public void delete(Long id) {
        MigrationTask task = requireTask(id);
        if (task.getState() == TaskState.STARTING
                || task.getState() == TaskState.SCHEMA_SNAPSHOTTING
                || task.getState() == TaskState.FULL
                || task.getState() == TaskState.INCREMENTAL
                || task.getState() == TaskState.STOPPING) {
            throw AppException.of(
                    ErrorCode.TASK_CONFLICT,
                    "Task " + id + " is " + task.getState() + " and must be stopped before delete");
        }
        offsetMapper.deleteByTaskId(id);
        schemaHistoryMapper.deleteByTaskId(id);
        taskMapper.deleteById(id);
    }

    private ConnectionInfo insertConnection(String name, io.opendrs.migration.api.request.ConnectionInfo config) {
        ConnectionInfo connection = new ConnectionInfo();
        connection.setName(name);
        connection.setType(config.type());
        connection.setHost(config.host());
        connection.setPort(config.port());
        connection.setDbName(config.database());
        connection.setUsername(config.username());
        connection.setPassword(config.password());
        connection.setExtra(config.extra());
        connectionMapper.insert(connection);
        return connection;
    }

    private MigrationTask requireTask(Long id) {
        MigrationTask task = taskMapper.findById(id);
        if (task == null) {
            throw AppException.of(ErrorCode.TASK_NOT_FOUND, "Task not found: " + id);
        }
        return task;
    }

    private ConnectionInfo requireConnection(Long id) {
        ConnectionInfo connection = connectionMapper.findById(id);
        if (connection == null) {
            throw AppException.of(ErrorCode.INTERNAL_ERROR, "Connection not found: " + id);
        }
        return connection;
    }

    private MigrationTaskResponse toResponse(MigrationTask task, ConnectionInfo source, ConnectionInfo target) {
        return new MigrationTaskResponse(
                task.getId(),
                task.getName(),
                task.getMode(),
                toDto(source).masked(),
                toDto(target).masked(),
                task.getTablesJson(),
                task.getOptionsJson(),
                task.getState(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }

    private MigrationTaskSummary toSummary(MigrationTask task) {
        ConnectionInfo source = requireConnection(task.getSourceConnectionId());
        ConnectionInfo target = requireConnection(task.getTargetConnectionId());
        return new MigrationTaskSummary(
                task.getId(),
                task.getName(),
                task.getMode(),
                task.getState(),
                new MigrationTaskSummary.SourceTargetType(source.getType()),
                new MigrationTaskSummary.SourceTargetType(target.getType()),
                task.getCreatedAt());
    }

    private MigrationStatusResponse toStatus(MigrationTask task) {
        return new MigrationStatusResponse(
                task.getId(),
                task.getState(),
                new MigrationStatusResponse.Progress(
                        task.getTablesTotal(),
                        task.getTablesDone(),
                        task.getRowsDone(),
                        task.getLagMs()),
                MigrationStatusResponse.Offset.empty(),
                task.getErrorMessage());
    }

    private static io.opendrs.migration.api.request.ConnectionInfo toDto(ConnectionInfo connection) {
        return new io.opendrs.migration.api.request.ConnectionInfo(
                connection.getType(),
                connection.getHost(),
                connection.getPort(),
                connection.getDbName(),
                connection.getUsername(),
                connection.getPassword(),
                connection.getExtra());
    }
}
