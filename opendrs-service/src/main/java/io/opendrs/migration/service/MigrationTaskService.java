package io.opendrs.migration.service;

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.common.log.MigrationLogLevel;
import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.migration.api.request.CreateMigrationTaskRequest;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.api.response.MigrationStatusResponse;
import io.opendrs.migration.api.response.MigrationTaskResponse;
import io.opendrs.migration.api.response.MigrationTaskSummary;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.engine.core.TaskState;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.domain.TaskPipeline;
import io.opendrs.migration.job.MigrationCoordinatorRegistry;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.DebeziumOffsetMapper;
import io.opendrs.migration.mapper.DebeziumSchemaHistoryMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import io.opendrs.migration.mapper.TaskPipelineMapper;
import io.opendrs.engine.replay.exception.MappingException;
import io.opendrs.engine.replay.mapping.MappingValidator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MigrationTaskService {

    private final MigrationTaskMapper taskMapper;
    private final ConnectionInfoMapper connectionMapper;
    private final DebeziumOffsetMapper offsetMapper;
    private final DebeziumSchemaHistoryMapper schemaHistoryMapper;
    private final TaskPipelineMapper pipelineMapper;
    private final MappingValidator mappingValidator;
    private final MigrationCoordinatorRegistry coordinatorRegistry;
    private final MigrationLogService logService;
    private final MigrationLagRegistry lagRegistry;
    private final SourcePositionQuery sourcePositionQuery;
    private final TransactionTemplate transactionTemplate;

    public MigrationTaskService(
            MigrationTaskMapper taskMapper,
            ConnectionInfoMapper connectionMapper,
            DebeziumOffsetMapper offsetMapper,
            DebeziumSchemaHistoryMapper schemaHistoryMapper,
            TaskPipelineMapper pipelineMapper,
            MappingValidator mappingValidator,
            MigrationCoordinatorRegistry coordinatorRegistry,
            MigrationLogService logService,
            MigrationLagRegistry lagRegistry,
            SourcePositionQuery sourcePositionQuery,
            TransactionTemplate transactionTemplate) {
        this.taskMapper = taskMapper;
        this.connectionMapper = connectionMapper;
        this.offsetMapper = offsetMapper;
        this.schemaHistoryMapper = schemaHistoryMapper;
        this.pipelineMapper = pipelineMapper;
        this.mappingValidator = mappingValidator;
        this.coordinatorRegistry = coordinatorRegistry;
        this.logService = logService;
        this.lagRegistry = lagRegistry;
        this.sourcePositionQuery = sourcePositionQuery;
        this.transactionTemplate = transactionTemplate;
    }

    @Transactional
    public MigrationTaskResponse create(CreateMigrationTaskRequest request) {
        MigrationOptions options = (request.options() == null
                ? MigrationOptions.defaults()
                : request.options().withDefaults())
                .withBidirectionalDefaults();
        validateSelection(options.selection());
        try {
            mappingValidator.validate(MappingRequests.toNeutral(options.selection().toTableSelection()));
        } catch (MappingException ex) {
            throw AppException.of(ErrorCode.PARAM_INVALID, ex.getMessage());
        }
        if (taskMapper.findByName(request.name()) != null) {
            throw AppException.of(ErrorCode.TASK_CONFLICT, "Task name already exists: " + request.name());
        }

        ConnectionInfo source = connectionMapper.findById(request.sourceConnectionId());
        if (source == null) {
            throw AppException.of(
                    ErrorCode.CONNECTION_NOT_FOUND,
                    "Source data source not found: " + request.sourceConnectionId());
        }
        ConnectionInfo target = connectionMapper.findById(request.targetConnectionId());
        if (target == null) {
            throw AppException.of(
                    ErrorCode.CONNECTION_NOT_FOUND,
                    "Target data source not found: " + request.targetConnectionId());
        }
        if (options.topology() == MigrationOptions.Topology.BIDIRECTIONAL) {
            if (source.toDatabaseType() != DatabaseType.MYSQL || target.toDatabaseType() != DatabaseType.MYSQL) {
                throw AppException.of(ErrorCode.PARAM_INVALID, "双向同步仅支持 MySQL 到 MySQL");
            }
            if (request.mode() != MigrationMode.FULL_AND_INCREMENTAL) {
                throw AppException.of(ErrorCode.PARAM_INVALID, "双向同步要求同步模式为 全量+增量");
            }
        }

        MigrationTask task = new MigrationTask();
        task.setPublicId(UUID.randomUUID().toString());
        task.setName(request.name());
        task.setMode(request.mode());
        task.setPhase(MigrationPhase.CREATED);
        task.setState(null);
        task.setSourceConnectionId(source.getId());
        task.setTargetConnectionId(target.getId());
        task.setOptionsJson(options);
        taskMapper.insert(task);
        createPipelines(task, options);
        return toResponse(requireTask(task.getId()), source, target);
    }

    /**
     * One pipeline row per physical direction: one-way tasks get one row, bidirectional tasks get
     * two (endpoints swapped). The synthetic full-load GTID namespace lives on the owning pipeline's
     * capture options — the sibling's drop set is derived from it at runtime, not persisted twice.
     */
    private void createPipelines(MigrationTask task, MigrationOptions options) {
        boolean bidirectional = options.topology() == MigrationOptions.Topology.BIDIRECTIONAL;
        insertPipeline(task, task.getSourceConnectionId(), task.getTargetConnectionId(),
                bidirectional
                        ? Map.of("fullGtidNamespace", options.bidirectional().forwardFullGtidNamespace())
                        : Map.of());
        if (bidirectional) {
            insertPipeline(task, task.getTargetConnectionId(), task.getSourceConnectionId(),
                    Map.of("fullGtidNamespace", options.bidirectional().reverseFullGtidNamespace()));
        }
    }

    private void insertPipeline(MigrationTask task, Long sourceEndpointId, Long targetEndpointId,
            Map<String, Object> captureOptions) {
        TaskPipeline pipeline = new TaskPipeline();
        pipeline.setTaskId(task.getId());
        pipeline.setSourceEndpointId(sourceEndpointId);
        pipeline.setTargetEndpointId(targetEndpointId);
        pipeline.setCaptureOptions(captureOptions);
        pipeline.setReplayOptions(Map.of());
        pipelineMapper.insert(pipeline);
    }

    /** The task's pipeline that runs from the task source to the task target; null when unknown. */
    static TaskPipeline forwardPipeline(MigrationTask task, List<TaskPipeline> pipelines) {
        return pipelines.stream()
                .filter(p -> p.getSourceEndpointId().equals(task.getSourceConnectionId())
                        && p.getTargetEndpointId().equals(task.getTargetConnectionId()))
                .findFirst()
                .orElse(pipelines.isEmpty() ? null : pipelines.get(0));
    }

    /** Mode must agree with the lists it scopes: ALL ⇒ nothing selected, SCHEMA ⇒ schemas only, TABLE ⇒ tables only. */
    private static void validateSelection(MigrationOptions.Selection selection) {
        boolean hasSchemas = selection.schemas() != null && !selection.schemas().isEmpty();
        boolean hasTables = selection.tables() != null && !selection.tables().isEmpty();
        switch (selection.mode()) {
            case ALL -> {
                if (hasSchemas || hasTables) {
                    throw AppException.of(
                            ErrorCode.PARAM_INVALID, "selection.mode=ALL requires empty schemas/tables");
                }
            }
            case SCHEMA -> {
                if (!hasSchemas || hasTables) {
                    throw AppException.of(
                            ErrorCode.PARAM_INVALID, "selection.mode=SCHEMA requires non-empty schemas and empty tables");
                }
            }
            case TABLE -> {
                if (hasSchemas || !hasTables) {
                    throw AppException.of(
                            ErrorCode.PARAM_INVALID, "selection.mode=TABLE requires non-empty tables and empty schemas");
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public List<MigrationTaskSummary> list() {
        return taskMapper.findAll().stream().map(this::toSummary).toList();
    }

    /**
     * Default name for the create wizard: DRS-xxxx where xxxx is the next auto-increment value.
     * Informational only — by the time the wizard submits, another insert may have taken the number,
     * in which case create() reports the duplicate name and the UI refetches.
     */
    @Transactional(readOnly = true)
    public String nextTaskName() {
        Long next = taskMapper.selectNextAutoIncrement();
        return "DRS-" + (next == null ? 1000 : next);
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
        transactionTemplate.executeWithoutResult(status -> {
            MigrationTask task = requireTask(id);
            if (!canStart(task)) {
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
            // The user confirmed the migration, so the pipeline has entered its structure step —
            // except bidirectional sync, which owns no schema migration and goes straight to the
            // data run. Same transaction as the STARTING CAS, so the dispatcher cannot pick up a
            // row whose phase still says PRECHECKED; state stays the thread-lifecycle axis.
            if (task.getPhase() == MigrationPhase.PRECHECKED) {
                MigrationPhase next = bidirectional(task) ? MigrationPhase.FULL : MigrationPhase.SCHEMA_MIGRATION;
                taskMapper.compareAndSetPhase(id, MigrationPhase.PRECHECKED, next);
            }
        });
        logService.log(id, MigrationLogLevel.INFO, "start requested by user");
        return toStatus(requireTask(id));
    }

    /**
     * 暂停: the resumable stop. A RUNNING task winds down through STOPPING (intent PAUSE) and lands
     * in STOPPED, from which start() resumes from the persisted offset. Only RUNNING/STARTING can
     * pause; STOPPED/STOPPING are idempotent no-ops, everything else is a conflict.
     */
    public MigrationStatusResponse stop(Long id) {
        MigrationTask task = requireTask(id);
        TaskState state = task.getState();
        if (state == TaskState.STOPPED || state == TaskState.STOPPING) {
            return toStatus(task);
        }
        if (state == TaskState.STARTING) {
            int updated = taskMapper.compareAndSetState(id, TaskState.STARTING, TaskState.STOPPED);
            if (updated == 0) {
                MigrationTask latest = requireTask(id);
                if (latest.getState() == TaskState.STOPPED || latest.getState() == TaskState.STOPPING) {
                    return toStatus(latest);
                }
                throw AppException.of(
                        ErrorCode.TASK_CONFLICT,
                        "Task " + id + " cannot be paused from state " + latest.getState());
            }
            return toStatus(requireTask(id));
        }
        if (state == TaskState.RUNNING) {
            int updated = taskMapper.markPausing(id);
            if (updated == 0) {
                MigrationTask latest = requireTask(id);
                if (latest.getState() == TaskState.STOPPED || latest.getState() == TaskState.STOPPING) {
                    return toStatus(latest);
                }
                throw AppException.of(
                        ErrorCode.TASK_CONFLICT,
                        "Task " + id + " cannot be paused from state " + latest.getState());
            }
            coordinatorRegistry.requestStop(id);
            logService.log(id, MigrationLogLevel.WARNING, "pause requested by user");
            return toStatus(requireTask(id));
        }
        throw AppException.of(
                ErrorCode.TASK_CONFLICT,
                "Task " + id + " cannot be paused from state " + state);
    }

    /**
     * 结束: the terminal stop — the user declares the task done and wants its resources released.
     * RUNNING winds down through TERMINATING (结束中) into TERMINATED; a pause still winding down
     * (STOPPING) is escalated by flipping it to TERMINATING; tasks with nothing running (never
     * started, still starting, paused, failed) go straight to TERMINATED. TERMINATED is idempotent;
     * from there the row may be deleted and start() refuses.
     */
    public MigrationStatusResponse terminate(Long id) {
        MigrationTask task = requireTask(id);
        TaskState state = task.getState();
        if (state == TaskState.TERMINATED) {
            return toStatus(task);
        }
        if (state == TaskState.COMPLETED) {
            // 全量任务的自然终态，已经结束无可释放 — 视为已结束，幂等返回。
            return toStatus(task);
        }
        if (state == TaskState.STOPPING) {
            taskMapper.flipStoppingToTerminating(id);
            coordinatorRegistry.requestStop(id);
            logService.log(id, MigrationLogLevel.WARNING, "terminate requested by user (escalating pause)");
            return toStatus(requireTask(id));
        }
        if (state == TaskState.RUNNING) {
            int updated = taskMapper.markTerminating(id);
            if (updated > 0) {
                coordinatorRegistry.requestStop(id);
                logService.log(id, MigrationLogLevel.WARNING, "terminate requested by user");
                return toStatus(requireTask(id));
            }
            // Lost the race (e.g. it just failed or was paused): fall through to the idle path.
            task = requireTask(id);
            state = task.getState();
            if (state == TaskState.STOPPING) {
                taskMapper.flipStoppingToTerminating(id);
                coordinatorRegistry.requestStop(id);
                return toStatus(requireTask(id));
            }
        }
        if (state == TaskState.STARTING) {
            coordinatorRegistry.requestStop(id);
        }
        int updated = taskMapper.terminateIdle(id);
        if (updated == 0) {
            MigrationTask latest = requireTask(id);
            if (latest.getState() == TaskState.TERMINATED) {
                return toStatus(latest);
            }
            throw AppException.of(
                    ErrorCode.TASK_CONFLICT,
                    "Task " + id + " cannot be terminated from state " + latest.getState());
        }
        logService.log(id, MigrationLogLevel.WARNING, "task terminated by user");
        return toStatus(requireTask(id));
    }

    @Transactional
    public void delete(Long id) {
        MigrationTask task = requireTask(id);
        if (task.getState() != TaskState.TERMINATED && task.getState() != TaskState.COMPLETED) {
            throw AppException.of(
                    ErrorCode.TASK_CONFLICT,
                    "Task " + id + " is " + describe(task) + " and must be terminated before delete");
        }
        offsetMapper.deleteByTaskId(id);
        schemaHistoryMapper.deleteByTaskId(id);
        logService.deleteByTaskId(id);
        // Offsets/history delete by subquery on task_pipelines, so pipelines go last among children.
        List<TaskPipeline> pipelines = pipelineMapper.findByTaskId(id);
        pipelineMapper.deleteByTaskId(id);
        pipelines.forEach(p -> lagRegistry.clear(p.getId()));
        taskMapper.deleteById(id);
    }

    /**
     * Startable states:
     *
     * <ul>
     *   <li>{@code PRECHECKED} with no state — precheck just passed and the user confirmed the
     *       migration in the wizard's 任务确认 step. This is the only path from a fresh task.
     *   <li>{@code STOPPED}/{@code FAILED} — resume an earlier run; the pipeline continues from the
     *       phase it reached.
     * </ul>
     *
     * <p>{@code CREATED} (never prechecked) and {@code PRECHECKING} (running or failed precheck) are
     * deliberately rejected: the migration must not start before a green precheck.
     */
    static boolean canStart(MigrationTask task) {
        MigrationPhase phase = task.getPhase();
        if (phase == MigrationPhase.CREATED || phase == MigrationPhase.PRECHECKING) {
            return false;
        }
        if (task.getState() == TaskState.STOPPED || task.getState() == TaskState.FAILED) {
            return true;
        }
        return phase == MigrationPhase.PRECHECKED && task.getState() == null;
    }

    private static String describe(MigrationTask task) {
        if (task.getState() != null) {
            return task.getState().name();
        }
        return task.getPhase().name();
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
                task.getPublicId(),
                task.getName(),
                task.getMode(),
                toDto(source).masked(),
                toDto(target).masked(),
                task.getOptionsJson(),
                task.getPhase(),
                task.getState(),
                task.getCreatedAt(),
                task.getUpdatedAt());
    }

    private MigrationTaskSummary toSummary(MigrationTask task) {
        ConnectionInfo source = requireConnection(task.getSourceConnectionId());
        ConnectionInfo target = requireConnection(task.getTargetConnectionId());
        return new MigrationTaskSummary(
                task.getId(),
                task.getPublicId(),
                task.getName(),
                task.getMode(),
                task.getPhase(),
                task.getState(),
                new MigrationTaskSummary.SourceTargetType(source.getType()),
                new MigrationTaskSummary.SourceTargetType(target.getType()),
                task.getCreatedAt(),
                lagMs(task, forwardPipeline(task, pipelineMapper.findByTaskId(task.getId()))));
    }

    private MigrationStatusResponse toStatus(MigrationTask task) {
        List<TaskPipeline> pipelines = pipelineMapper.findByTaskId(task.getId());
        TaskPipeline forward = forwardPipeline(task, pipelines);
        TaskPipeline reverse = null;
        if (pipelines.size() > 1) {
            reverse = pipelines.stream().filter(p -> p != forward).findFirst().orElse(null);
        }
        return new MigrationStatusResponse(
                task.getId(),
                task.getPhase(),
                task.getState(),
                new MigrationStatusResponse.Progress(lagMs(task, forward)),
                forward == null ? null : offsetOf(task, forward),
                reverse == null ? null : offsetOf(task, reverse),
                task.getErrorMessage());
    }

    private static boolean bidirectional(MigrationTask task) {
        MigrationOptions options = task.getOptionsJson();
        return options != null && options.topology() == MigrationOptions.Topology.BIDIRECTIONAL;
    }

    /**
     * Positions along one pipeline for the detail page. The capture/apply pair comes from the
     * registry the engine feeds, keyed by pipeline id; the source position is queried live on the
     * pipeline's source endpoint (briefly cached). Anything unavailable shows as null and the UI
     * renders a dash.
     */
    private MigrationStatusResponse.Offset offsetOf(MigrationTask task, TaskPipeline pipeline) {
        MigrationLagRegistry.Positions positions =
                lagRegistry.positions(pipeline.getId()).orElse(null);
        String captured = positions == null ? null : positions.captured();
        String applied = positions == null ? null : positions.applied();
        long updatedAtMs = positions == null
                ? 0L
                : Math.max(positions.capturedAtMs(), positions.appliedAtMs());
        return new MigrationStatusResponse.Offset(
                sourcePositionQuery.query(connectionMapper.findById(pipeline.getSourceEndpointId())),
                captured,
                applied,
                updatedAtMs > 0
                        ? java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                .withZone(java.time.ZoneId.systemDefault())
                                .format(java.time.Instant.ofEpochMilli(updatedAtMs))
                        : null);
    }

    /**
     * Live lag, computed at read time from the timestamps the capture path published.
     *
     * <p>Only meaningful once the snapshot is done and the binlog is being followed: during the
     * full load every "event" is a snapshot row whose timestamp is the read time, and after the run
     * ends the registry entry describes a pipeline that no longer exists. Everywhere else the column
     * shows a dash rather than a stale number.
     */
    private Long lagMs(MigrationTask task, TaskPipeline forward) {
        if (task.getState() != TaskState.RUNNING || task.getPhase() != MigrationPhase.INCREMENTAL) {
            return null;
        }
        if (forward == null) {
            return null;
        }
        return lagRegistry.lagMs(forward.getId()).orElse(null);
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
