package io.opendrs.migration.job;

import io.opendrs.common.log.MigrationLogLevel;
import io.opendrs.common.log.MigrationLogListener;
import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.common.progress.MigrationLagListener;
import io.opendrs.engine.core.DataMigrationParam;
import io.opendrs.engine.core.DataMigrationTask;
import io.opendrs.engine.core.DataMigrationTaskPhase;
import io.opendrs.engine.core.MigrationEngine;
import io.opendrs.engine.core.SchemaMigrationParam;
import io.opendrs.engine.core.SchemaMigrationTask;
import io.opendrs.engine.core.TaskState;
import io.opendrs.engine.replay.GtidLoopConfig;
import io.opendrs.engine.replay.mapping.TableSelection;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import io.opendrs.migration.service.MappingRequests;
import io.opendrs.migration.service.ServerIdAllocator;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinator thread for one task: owns the persisted state machine (phase CAS, failure, terminal
 * state) and asks the engine for the two runs.
 *
 * <p>The engine returns handles, so this thread never runs the capture itself — it starts
 * {@code migrateSchema} and then {@code migrateData} and parks on {@code await()}. Stopping is
 * cooperative: {@link #requestStop()} forwards to the current handle and lets the engine wind down,
 * which is why the worker thread is never interrupted. Interrupting it would only break the wait
 * while the engine kept streaming.
 */
public class MigrationCoordinator implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MigrationCoordinator.class);

    private static final int ERROR_MESSAGE_MAX = 1024;

    private final Long taskId;
    private final MigrationMode mode;
    private final MigrationTaskMapper taskMapper;
    private final ConnectionInfoMapper connectionMapper;
    private final MigrationCoordinatorRegistry registry;
    private final MigrationEngine engine;
    private final MigrationLogListener logListener;
    private final java.util.function.Function<Long, MigrationLagListener> lagListenerFactory;
    private final ServerIdAllocator serverIdAllocator;
    private final io.opendrs.migration.mapper.TaskPipelineMapper pipelineMapper;
    private final io.opendrs.migration.service.FullLoadResumePlanner resumePlanner;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Thread worker;

    /**
     * The handles currently awaited. Deliberately fully qualified: the engine's {@code MigrationTask}
     * shares its simple name with the persisted entity, and the entity keeps the plain import
     * because it appears everywhere in this class. Bidirectional tasks run two directions, so
     * there are two handles.
     */
    private volatile java.util.List<io.opendrs.engine.core.MigrationTask> currentTasks =
            java.util.List.of();

    /**
     * How many of this run's directions must report INCREMENTAL before the task phase advances.
     * One for single-direction tasks, two for bidirectional ones.
     */
    private volatile int expectedDirections = 1;

    /** Direction keys that have entered INCREMENTAL during this run. */
    private final java.util.Set<String> incrementalDirections = new java.util.concurrent.ConcurrentHashMap<String, Boolean>().keySet(true);

    /**
     * FULL_ONLY 专用：快照完成通知已到达。Debezium 的 initial_only 只让协调器跳过 streaming
     * 循环，内嵌引擎（AsyncEmbeddedEngine）并不会因此结束——复现程序实测 3.6.1 与 3.7.0 都永久
     * 挂起在 POLLING_TASKS。所以快照回放完后必须由协调器主动停机，任务才能落库 COMPLETED。
     * 该标志可能在 track() 之前被引擎线程置位（信号与注册存在竞态），两处都要消费它。
     */
    private volatile boolean fullLoadSnapshotDone = false;

    public MigrationCoordinator(Long taskId, MigrationMode mode, MigrationTaskMapper taskMapper, MigrationCoordinatorRegistry registry) {
        this(taskId, mode, taskMapper, null, registry, null, MigrationLogListener.NONE);
    }

    public MigrationCoordinator(
            Long taskId,
            MigrationMode mode,
            MigrationTaskMapper taskMapper,
            ConnectionInfoMapper connectionMapper,
            MigrationCoordinatorRegistry registry,
            MigrationEngine engine) {
        this(taskId, mode, taskMapper, connectionMapper, registry, engine, MigrationLogListener.NONE);
    }

    /**
     * @param logListener receives the job's own narration plus everything the engine reports; the
     *     coordinator thread passes it on to the engine so both sides land in the same task log
     */
    public MigrationCoordinator(
            Long taskId,
            MigrationMode mode,
            MigrationTaskMapper taskMapper,
            ConnectionInfoMapper connectionMapper,
            MigrationCoordinatorRegistry registry,
            MigrationEngine engine,
            MigrationLogListener logListener) {
        this(taskId, mode, taskMapper, connectionMapper, registry, engine, logListener, MigrationLagListener.NONE);
    }

    /**
     * @param logListener receives the job's own narration plus everything the engine reports; the
     *     coordinator thread passes it on to the engine so both sides land in the same task log
     * @param lagListener fed by the capture path after each committed batch; the coordinator only
     *     hands it to the engine, it never reads it
     */
    public MigrationCoordinator(
            Long taskId,
            MigrationMode mode,
            MigrationTaskMapper taskMapper,
            ConnectionInfoMapper connectionMapper,
            MigrationCoordinatorRegistry registry,
            MigrationEngine engine,
            MigrationLogListener logListener,
            MigrationLagListener lagListener) {
        this(taskId, mode, taskMapper, connectionMapper, registry, engine, logListener, lagListener, null);
    }

    /**
     * @param logListener receives the job's own narration plus everything the engine reports; the
     *     coordinator thread passes it on to the engine so both sides land in the same task log
     * @param lagListener fed by the capture path after each committed batch; the coordinator only
     *     hands it to the engine, it never reads it
     * @param serverIdAllocator picks a free Debezium {@code database.server.id} per start when the
     *     task options don't pin one; null in unit tests, where no source is reachable anyway
     */
    public MigrationCoordinator(
            Long taskId,
            MigrationMode mode,
            MigrationTaskMapper taskMapper,
            ConnectionInfoMapper connectionMapper,
            MigrationCoordinatorRegistry registry,
            MigrationEngine engine,
            MigrationLogListener logListener,
            MigrationLagListener lagListener,
            ServerIdAllocator serverIdAllocator) {
        this(taskId, mode, taskMapper, connectionMapper, registry, engine, logListener,
                lagListener, serverIdAllocator, null);
    }

    /**
     * @param lagListenerFactory hands out one lag listener per pipeline: unidirectional tasks use
     *     their single pipeline, bidirectional tasks key each direction's own progress by pipeline
     *     id. When null, every pipeline shares {@code lagListener}.
     */
    public MigrationCoordinator(
            Long taskId,
            MigrationMode mode,
            MigrationTaskMapper taskMapper,
            ConnectionInfoMapper connectionMapper,
            MigrationCoordinatorRegistry registry,
            MigrationEngine engine,
            MigrationLogListener logListener,
            MigrationLagListener lagListener,
            ServerIdAllocator serverIdAllocator,
            java.util.function.Function<Long, MigrationLagListener> lagListenerFactory) {
        this(taskId, mode, taskMapper, connectionMapper, registry, engine, logListener,
                lagListener, serverIdAllocator, lagListenerFactory, null);
    }

    /**
     * @param pipelineMapper loads the task's pipeline rows; null in unit tests, where an ephemeral
     *     single pipeline synthesized from the task row stands in
     */
    public MigrationCoordinator(
            Long taskId,
            MigrationMode mode,
            MigrationTaskMapper taskMapper,
            ConnectionInfoMapper connectionMapper,
            MigrationCoordinatorRegistry registry,
            MigrationEngine engine,
            MigrationLogListener logListener,
            MigrationLagListener lagListener,
            ServerIdAllocator serverIdAllocator,
            java.util.function.Function<Long, MigrationLagListener> lagListenerFactory,
            io.opendrs.migration.mapper.TaskPipelineMapper pipelineMapper) {
        this(taskId, mode, taskMapper, connectionMapper, registry, engine, logListener,
                lagListener, serverIdAllocator, lagListenerFactory, pipelineMapper, null);
    }

    /**
     * @param pipelineMapper loads the task's pipeline rows; null in unit tests, where an ephemeral
     *     single pipeline synthesized from the task row stands in
     * @param resumePlanner decides per-pipeline full-load resume (remaining tables from the
     *     persisted checkpoints); null in unit tests, where no checkpoint DB is reachable
     */
    public MigrationCoordinator(
            Long taskId,
            MigrationMode mode,
            MigrationTaskMapper taskMapper,
            ConnectionInfoMapper connectionMapper,
            MigrationCoordinatorRegistry registry,
            MigrationEngine engine,
            MigrationLogListener logListener,
            MigrationLagListener lagListener,
            ServerIdAllocator serverIdAllocator,
            java.util.function.Function<Long, MigrationLagListener> lagListenerFactory,
            io.opendrs.migration.mapper.TaskPipelineMapper pipelineMapper,
            io.opendrs.migration.service.FullLoadResumePlanner resumePlanner) {
        this.taskId = taskId;
        this.mode = mode;
        this.taskMapper = taskMapper;
        this.connectionMapper = connectionMapper;
        this.registry = registry;
        this.engine = engine;
        this.logListener = logListener == null ? MigrationLogListener.NONE : logListener;
        MigrationLagListener fallback = lagListener == null ? MigrationLagListener.NONE : lagListener;
        this.lagListenerFactory = lagListenerFactory == null
                ? (id) -> fallback
                : lagListenerFactory;
        this.serverIdAllocator = serverIdAllocator;
        this.pipelineMapper = pipelineMapper;
        this.resumePlanner = resumePlanner;
    }

    public Long getTaskId() {
        return taskId;
    }

    public boolean isRunning() {
        Thread current = worker;
        return current != null && current.isAlive();
    }

    /**
     * Occupies the registry slot: queued (thread not started) or still alive.
     * A dead thread is not occupying, so the dispatcher may replace the zombie.
     */
    boolean occupiesSlot() {
        Thread current = worker;
        return current == null || current.isAlive();
    }

    /**
     * Asks the engine to wind down. Safe to call before the worker starts, between the two runs and
     * while the capture streams: the flag covers the gaps, the handles cover the running parts.
     */
    public void requestStop() {
        stopRequested.set(true);
        for (io.opendrs.engine.core.MigrationTask task : currentTasks) {
            task.requestStop();
        }
    }

    @Override
    public void run() {
        worker = Thread.currentThread();
        try {
            if (!claimRunning()) {
                return;
            }
            note(MigrationLogLevel.INFO, "job started, mode " + mode);
            if (stopRequested()) {
                finishStopped();
                return;
            }
            runPipeline();
            if (mode == MigrationMode.FULL_ONLY && !stopRequested()) {
                finishCompleted();
            } else {
                finishStopped();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finishStopped();
            log.info("Task job {} interrupted", taskId);
        } catch (Exception ex) {
            fail(ex);
        } finally {
            currentTasks = java.util.List.of();
            registry.remove(taskId, this);
        }
    }

    /** STARTING → RUNNING, or confirms the row is still RUNNING. */
    private boolean claimRunning() {
        MigrationTask task = taskMapper.findById(taskId);
        if (task == null) {
            return false;
        }
        if (task.getState() == TaskState.STARTING) {
            return taskMapper.compareAndSetState(taskId, TaskState.STARTING, TaskState.RUNNING) > 0;
        }
        return task.getState() == TaskState.RUNNING;
    }

    void runPipeline() throws InterruptedException {
        if (engine == null) {
            throw new IllegalStateException("MigrationEngine is not configured for task " + taskId);
        }
        boolean bidirectional = bidirectional();
        MigrationPhase phase = currentPhase();
        if (phase == MigrationPhase.PRECHECKED) {
            // Bidirectional sync owns no schema migration: both sides must already hold the tables.
            advancePhase(MigrationPhase.PRECHECKED, bidirectional ? MigrationPhase.FULL : MigrationPhase.SCHEMA_MIGRATION);
            phase = currentPhase();
        }
        if (phase == MigrationPhase.SCHEMA_MIGRATION) {
            // Re-entrant on purpose: the phase is set before the DDL runs, so a restart lands here
            // with a possibly half-created target. SchemaMigrator skips tables that already exist.
            runSchemaMigration();
            if (stopRequested()) {
                return;
            }
            advancePhase(MigrationPhase.SCHEMA_MIGRATION, MigrationPhase.FULL);
            phase = MigrationPhase.FULL;
        }
        if (phase == MigrationPhase.FULL || phase == MigrationPhase.INCREMENTAL) {
            runDataMigration();
            return;
        }
        log.info("Task job {} phase {} has nothing to run, leaving it alone", taskId, phase);
    }

    private void runSchemaMigration() throws InterruptedException {
        MigrationTask task = requireTask();
        ConnectionInfo source = requireConnection(task.getSourceConnectionId());
        ConnectionInfo target = requireConnection(task.getTargetConnectionId());
        SchemaMigrationTask handle = engine.migrateSchema(SchemaMigrationParam.builder()
                .source(source.toJdbcConfig())
                .target(target.toJdbcConfig())
                .selection(selection(task))
                .logListener(logListener)
                .build());
        track(java.util.List.of(handle));
        awaitEngine(handle);
        checkEngineFailure(handle, "Schema migration failed");
        log.info(
                "Task job {} schema migration finished: {} table(s) created, {} skipped",
                taskId,
                handle.created().size(),
                handle.skipped().size());
        note(MigrationLogLevel.INFO, "schema migration finished: " + handle.created().size()
                + " created, " + handle.skipped().size() + " skipped");
    }

    private void runDataMigration() throws InterruptedException {
        MigrationTask task = requireTask();
        java.util.List<io.opendrs.migration.domain.TaskPipeline> pipelines = pipelinesOf(task);
        expectedDirections = pipelines.size();
        incrementalDirections.clear();

        java.util.List<io.opendrs.engine.core.MigrationTask> handles = new java.util.ArrayList<>(pipelines.size());
        try {
            for (io.opendrs.migration.domain.TaskPipeline pipeline : pipelines) {
                handles.add(engine.migrateData(pipelineParam(task, pipeline, pipelines)));
            }
            track(handles);
            // 竞态收口：FULL_ONLY 的快照完成信号若先于 track() 到达（假引擎测试里会发生），
            // 停机线程看到的 currentTasks 还是空的，这里补一次。
            if (fullLoadSnapshotDone) {
                stopPipelinesForFullLoad();
            }
            log.info("Task job {} mode {} started data migration on {} pipeline(s)",
                    taskId, mode, pipelines.size());
            note(MigrationLogLevel.INFO, mode == MigrationMode.FULL_ONLY
                    ? "data migration started, full load only"
                    : "data migration started, snapshot then incremental");
            for (io.opendrs.engine.core.MigrationTask handle : handles) {
                awaitEngine(handle);
            }
        } finally {
            // A failure or a stop of one pipeline must bring the others down too — a dead
            // direction cannot keep writing while the task is FAILED.
            for (io.opendrs.engine.core.MigrationTask handle : handles) {
                handle.requestStop();
            }
        }
        for (io.opendrs.engine.core.MigrationTask handle : handles) {
            checkEngineFailure(handle, "Data migration failed");
        }
        if (!stopRequested() && mode != MigrationMode.FULL_ONLY) {
            throw new IllegalStateException("CDC engine for task " + taskId + " ended without a stop request");
        }
        // 全量模式：引擎是被 fullLoadSnapshotDone 触发的主动停机结束的（Debezium 的
        // initial_only 不会自行结束内嵌引擎），run() 会据此把任务落库为 COMPLETED。
    }

    /**
     * FULL_ONLY 的快照回放完成停机。必须在引擎自己的回调线程之外执行：engine.close() 会
     * 停掉并等待引擎的轮询/处理线程，从其中一条线程内部发起有自连接风险，所以 onTaskPhase
     * 用独立线程调用这里。
     */
    private void stopPipelinesForFullLoad() {
        for (io.opendrs.engine.core.MigrationTask handle : currentTasks) {
            handle.requestStop();
        }
    }

    /** The task's pipeline rows; unit tests without a mapper get one ephemeral row from the task. */
    private java.util.List<io.opendrs.migration.domain.TaskPipeline> pipelinesOf(MigrationTask task) {
        if (pipelineMapper != null) {
            java.util.List<io.opendrs.migration.domain.TaskPipeline> pipelines = pipelineMapper.findByTaskId(task.getId());
            if (!pipelines.isEmpty()) {
                return pipelines;
            }
        }
        io.opendrs.migration.domain.TaskPipeline ephemeral = new io.opendrs.migration.domain.TaskPipeline();
        ephemeral.setId(task.getId());
        ephemeral.setTaskId(task.getId());
        ephemeral.setSourceEndpointId(task.getSourceConnectionId());
        ephemeral.setTargetEndpointId(task.getTargetConnectionId());
        ephemeral.setCaptureOptions(java.util.Map.of());
        ephemeral.setReplayOptions(java.util.Map.of());
        return java.util.List.of(ephemeral);
    }

    /**
     * 同步模式 → 引擎快照策略：全量 = 快照完自动停（initial_only，引擎自然结束）；
     * 增量 = 只抓结构后追日志（no_data）；全量+增量 = initial。
     */
    private static io.opendrs.engine.replay.SnapshotStrategy strategyOf(MigrationMode mode) {
        if (mode == MigrationMode.FULL_ONLY) {
            return io.opendrs.engine.replay.SnapshotStrategy.INITIAL_ONLY;
        }
        if (mode == MigrationMode.INCREMENTAL_ONLY) {
            return io.opendrs.engine.replay.SnapshotStrategy.NO_DATA;
        }
        return io.opendrs.engine.replay.SnapshotStrategy.INITIAL;
    }

    private DataMigrationParam pipelineParam(
            MigrationTask task,
            io.opendrs.migration.domain.TaskPipeline pipeline,
            java.util.List<io.opendrs.migration.domain.TaskPipeline> pipelines) {
        ConnectionInfo source = requireConnection(pipeline.getSourceEndpointId());
        ConnectionInfo target = requireConnection(pipeline.getTargetEndpointId());
        MigrationOptions options = task.getOptionsJson();
        Integer txApplierConcurrency = options != null && options.incremental() != null
                ? options.incremental().replayConcurrency() : null;
        Integer tableApplierConcurrency = options != null && options.full() != null
                ? options.full().importConcurrency() : null;
        Integer snapshotThreads = options != null && options.full() != null
                ? options.full().exportConcurrency() : null;
        Integer snapshotThreadsMultiplier = options != null && options.full() != null
                ? options.full().chunkMultiplier() : null;
        io.opendrs.engine.replay.metrics.ReplayDirection direction = java.util.Objects.equals(
                pipeline.getSourceEndpointId(), task.getSourceConnectionId())
                ? io.opendrs.engine.replay.metrics.ReplayDirection.FORWARD
                : io.opendrs.engine.replay.metrics.ReplayDirection.REVERSE;
        return DataMigrationParam.builder()
                .taskId(taskId)
                .pipelineId(pipeline.getId())
                .source(source.toJdbcConfig())
                .target(target.toJdbcConfig())
                .selection(selection(task))
                .databaseServerId(serverIdOf(source))
                .gtidLoop(gtidLoopOf(pipeline, pipelines))
                .direction(direction)
                .txApplierConcurrency(txApplierConcurrency)
                .tableApplierConcurrency(tableApplierConcurrency)
                .snapshotThreads(snapshotThreads)
                .snapshotThreadsMultiplier(snapshotThreadsMultiplier)
                .snapshotStrategy(strategyOf(mode))
                .resumeTables(resumeTablesOf(task, pipeline, source, target, direction))
                .phaseListener(phase -> onTaskPhase(phase, pipeline))
                .logListener(logListener)
                .lagListener(lagListenerFactory.apply(pipeline.getId()))
                .build();
    }

    /**
     * 全量断点续传：引擎启动前按 (taskId, direction) 读 checkpoint，done 表跳过、半成品表
     * TRUNCATE 后与剩余表一起重拍。
     *
     * <p>两种快照策略都参与，但语义不同：
     * <ul>
     *   <li>{@code INITIAL}（全量+增量）：位点 P0 复用，快照完从 P0 续流（引擎侧
     *   ResumableInitialSnapshotter）。</li>
     *   <li>{@code INITIAL_ONLY}（仅全量）：纯快照续传——done 表跳过、半成品表 TRUNCATE
     *   后重导，不依赖 binlog 位点。前提是仅全量任务隐含源库离线/静止：跳过的表不会
     *   与源端重新核对，崩溃期间源端若有变更，目标端不会察觉（仅全量本身也不捕获这类
     *   变更，续传只是把同一假设贯彻到底）。</li>
     * </ul>
     * {@code NO_DATA}（增量）没有快照，不参与。
     *
     * <p>退化与限制：
     * <ul>
     *   <li>binlog 保留期必须覆盖整个全量窗口（仅 INITIAL 策略涉及）。P0 被 purge 时
     *   Debezium 会报错失败，下次重启还会在同一位置失败——需要人工清掉目标库全量数据后
     *   从头重跑（无显式检测）。</li>
     *   <li>无主键表不参与续传：半成品表 TRUNCATE 后重导依赖 dup-key→upsert 消化重复行，
     *   无 PK 的表无法 upsert，预检查已将其挡在任务外。</li>
     * </ul>
     *
     * @return remaining 表集合（非空时引擎只快照这些表），{@code null} 时不续传、完全走
     *     现有全量路径
     */
    private java.util.Set<String> resumeTablesOf(
            MigrationTask task,
            io.opendrs.migration.domain.TaskPipeline pipeline,
            ConnectionInfo source,
            ConnectionInfo target,
            io.opendrs.engine.replay.metrics.ReplayDirection direction) {
        io.opendrs.engine.replay.SnapshotStrategy strategy = strategyOf(mode);
        if (resumePlanner == null
                || (strategy != io.opendrs.engine.replay.SnapshotStrategy.INITIAL
                        && strategy != io.opendrs.engine.replay.SnapshotStrategy.INITIAL_ONLY)) {
            return null;
        }
        java.util.Set<String> remaining =
                resumePlanner.resumeTables(taskId, pipeline.getId(), selection(task), source, target);
        if (remaining == null) {
            return null;
        }
        if (remaining.isEmpty()) {
            // 全部表已 done：不传 resumeTables，引擎按 INITIAL 正常启动——存量位点存在时
            // Debezium 跳过快照直接进入增量，这正是"快照完成直接进增量"的现有路径。
            return null;
        }
        log.info("Task job {} direction {} resumes full load, {} table(s) to re-snapshot",
                taskId, direction.tag(), remaining.size());
        note(MigrationLogLevel.INFO, "resume full load: " + remaining.size()
                + " table(s) to re-snapshot, " + "done table(s) skipped");
        if (strategy == io.opendrs.engine.replay.SnapshotStrategy.INITIAL_ONLY) {
            // FULL_ONLY 续传的离线前提必须留在任务日志里：跳过的表不会与源端重新核对。
            note(MigrationLogLevel.WARNING,
                    "FULL_ONLY resume assumes the source is unchanged since the previous run;"
                            + " skipped tables are not re-verified");
        }
        return remaining;
    }

    /**
     * Loop prevention for a pipeline that belongs to a bidirectional pair: drop whatever the
     * opposite sink wrote — the peer server's own GTID UUID (incremental replays carry the source
     * record's GTID) and the opposite direction's synthetic full-load namespace (snapshot
     * batches). A one-way pipeline has no sibling and no loop to break, so it runs without one.
     */
    private GtidLoopConfig gtidLoopOf(
            io.opendrs.migration.domain.TaskPipeline pipeline,
            java.util.List<io.opendrs.migration.domain.TaskPipeline> pipelines) {
        if (pipelines.size() < 2) {
            return null;
        }
        io.opendrs.migration.domain.TaskPipeline sibling =
                pipelines.stream().filter(p -> p != pipeline).findFirst().orElse(null);
        if (sibling == null) {
            return null;
        }
        java.util.Set<String> drop = new java.util.HashSet<>();
        if (serverIdAllocator != null) {
            drop.add(serverIdAllocator.serverUuid(requireConnection(pipeline.getTargetEndpointId())));
        }
        String siblingNamespace = fullGtidNamespace(sibling);
        if (siblingNamespace != null) {
            drop.add(siblingNamespace);
        }
        return new GtidLoopConfig(drop, fullGtidNamespace(pipeline));
    }

    private static String fullGtidNamespace(io.opendrs.migration.domain.TaskPipeline pipeline) {
        java.util.Map<String, Object> capture = pipeline.getCaptureOptions();
        Object namespace = capture == null ? null : capture.get("fullGtidNamespace");
        return namespace == null ? null : String.valueOf(namespace);
    }

    private Long serverIdOf(ConnectionInfo connection) {
        if (serverIdAllocator == null || connection.toDatabaseType() != DatabaseType.MYSQL) {
            return null;
        }
        return serverIdAllocator.allocate(connection);
    }

    private boolean bidirectional() {
        MigrationTask task = taskMapper.findById(taskId);
        MigrationOptions options = task == null ? null : task.getOptionsJson();
        return options != null && options.topology() == MigrationOptions.Topology.BIDIRECTIONAL;
    }

    /**
     * Engine callback: called on the engine worker thread, so it only touches the mapper.
     *
     * <p>This is where the engine's own phase meets the service's {@link MigrationPhase}. They are two
     * different levels: {@code MigrationPhase} is the whole-task pipeline the UI draws and owns
     * {@code CREATED/PRECHECKING/PRECHECKED}, which the engine has no concept of, while the engine
     * reports what the migration is physically doing. From {@code SCHEMA_MIGRATION} on they line up
     * one to one, so this callback only translates {@code INCREMENTAL} — the coordinator itself
     * sets {@code FULL} before it starts the data run. A bidirectional task advances only once
     * <em>both</em> directions stream.
     */
    private void onTaskPhase(DataMigrationTaskPhase taskPhase, io.opendrs.migration.domain.TaskPipeline pipeline) {
        log.info("Task job {} pipeline {} entered phase {}", taskId, pipeline.getId(), taskPhase);
        if (taskPhase == DataMigrationTaskPhase.INCREMENTAL && mode == MigrationMode.FULL_ONLY) {
            // 全量模式：快照完成通知意味着全部存量数据已同步回放完毕（全量回放是同步的，
            // 通知在最后一个数据批次应用之后才触发）。phase 保持 FULL 不再前进，由
            // stopPipelinesForFullLoad 主动停机，run() 随后把任务落库为 COMPLETED。
            fullLoadSnapshotDone = true;
            log.info("Task job {} FULL_ONLY: snapshot fully replayed, stopping pipelines", taskId);
            note(MigrationLogLevel.INFO, "full load finished, completing task");
            Thread stopper = new Thread(this::stopPipelinesForFullLoad, "full-load-stop-" + taskId);
            stopper.setDaemon(true);
            stopper.start();
            return;
        }
        if (taskPhase == DataMigrationTaskPhase.INCREMENTAL) {
            incrementalDirections.add(String.valueOf(pipeline.getId()));
            if (incrementalDirections.size() >= expectedDirections) {
                enterIncremental();
            }
        }
    }

    private void enterIncremental() {
        MigrationPhase phase = currentPhase();
        if (phase == MigrationPhase.INCREMENTAL) {
            return;
        }
        if (phase != MigrationPhase.FULL) {
            log.warn("Task job {} cannot enter INCREMENTAL from phase {}", taskId, phase);
            return;
        }
        advancePhase(MigrationPhase.FULL, MigrationPhase.INCREMENTAL);
    }

    private void advancePhase(MigrationPhase from, MigrationPhase to) {
        int updated = taskMapper.compareAndSetPhase(taskId, from, to);
        if (updated == 0) {
            log.warn(
                    "Task job {} phase advance {} -> {} skipped, actual phase {}",
                    taskId,
                    from,
                    to,
                    currentPhase());
            return;
        }
        note(MigrationLogLevel.INFO, "phase " + from + " -> " + to);
    }

    /**
     * Publishes the handles and closes the race with {@link #requestStop()}: a stop that arrives
     * while the engine is still preparing has no handle to talk to, so it is replayed here.
     */
    private void track(java.util.List<io.opendrs.engine.core.MigrationTask> handles) {
        currentTasks = handles;
        if (stopRequested.get()) {
            for (io.opendrs.engine.core.MigrationTask handle : handles) {
                handle.requestStop();
            }
        }
    }

    private void awaitEngine(io.opendrs.engine.core.MigrationTask handle) throws InterruptedException {
        try {
            handle.await();
        } catch (InterruptedException ex) {
            handle.requestStop();
            throw ex;
        }
    }

    private void checkEngineFailure(io.opendrs.engine.core.MigrationTask handle, String what) {
        Throwable failure = handle.failure();
        if (failure == null) {
            return;
        }
        throw new IllegalStateException(what + ": " + describe(failure), failure);
    }

    /**
     * Flattens the cause chain into one line. The outermost message is the engine's own wrapper
     * ("Debezium Engine failed for task N"); the actionable text sits in the cause, and the UI only
     * ever sees {@code error_message}. Links are skipped when they add nothing: JDK wrappers such as
     * {@code new RuntimeException(cause)} and {@code ExecutionException} copy {@code cause.toString()}
     * into their own message, which would otherwise render the same text twice.
     */
    static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 10; depth++) {
            Throwable next = current.getCause() == current ? null : current.getCause();
            String message = current.getMessage();
            boolean echoesCause = next != null && message != null && message.equals(String.valueOf(next));
            if (message != null && !message.isBlank() && !echoesCause && text.indexOf(message) < 0) {
                if (!text.isEmpty()) {
                    text.append(" <- ");
                }
                text.append(message);
            }
            current = next;
        }
        return text.isEmpty() ? failure.getClass().getSimpleName() : text.toString();
    }

    private TableSelection selection(MigrationTask task) {
        MigrationOptions options = task.getOptionsJson();
        if (options == null || options.selection() == null) {
            return null;
        }
        return MappingRequests.toNeutral(options.selection().toTableSelection());
    }

    private MigrationPhase currentPhase() {
        MigrationTask task = taskMapper.findById(taskId);
        return task == null ? null : task.getPhase();
    }

    private boolean stopRequested() {
        return stopRequested.get();
    }

    private MigrationTask requireTask() {
        MigrationTask task = taskMapper.findById(taskId);
        if (task == null) {
            throw new IllegalStateException("Task not found: " + taskId);
        }
        return task;
    }

    private ConnectionInfo requireConnection(Long connectionId) {
        ConnectionInfo connection = connectionId == null ? null : connectionMapper.findById(connectionId);
        if (connection == null) {
            throw new IllegalStateException("Connection " + connectionId + " not found for task " + taskId);
        }
        return connection;
    }

    private void fail(Exception ex) {
        log.error("Task job {} failed", taskId, ex);
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        if (message.length() > ERROR_MESSAGE_MAX) {
            message = message.substring(0, ERROR_MESSAGE_MAX);
        }
        taskMapper.markFailed(taskId, message);
        note(MigrationLogLevel.ERROR, "job failed: " + message);
    }

    private void finishStopped() {
        MigrationTask task = taskMapper.findById(taskId);
        if (task == null) {
            return;
        }
        TaskState state = task.getState();
        if (state == TaskState.STOPPED || state == TaskState.FAILED || state == TaskState.TERMINATED) {
            return;
        }
        // 暂停中 lands 已暂停, 结束中 lands 已结束 — the wind-down state itself is the intent.
        if (state == TaskState.STOPPING) {
            if (taskMapper.landPausing(taskId) > 0) {
                note(MigrationLogLevel.WARNING, "job paused");
            }
            return;
        }
        if (state == TaskState.TERMINATING) {
            if (taskMapper.landTerminating(taskId) > 0) {
                note(MigrationLogLevel.WARNING, "job terminated");
            }
            return;
        }
        if (state == TaskState.RUNNING) {
            // Defensive: wind-down state never got written (e.g. crash between request and CAS).
            if (taskMapper.compareAndSetState(taskId, TaskState.RUNNING, TaskState.STOPPED) > 0) {
                note(MigrationLogLevel.WARNING, "job paused");
            }
        }
    }

    /**
     * 全量模式正常跑完：连接器 initial_only 快照完成后自行结束，引擎 handle 走到 COMPLETED。
     * 这是有限任务的自然终态 —— 任务不可再启动，允许直接删除（同 TERMINATED）。
     */
    private void finishCompleted() {
        MigrationTask task = taskMapper.findById(taskId);
        if (task == null) {
            return;
        }
        if (task.getState() == TaskState.RUNNING
                && taskMapper.compareAndSetState(taskId, TaskState.RUNNING, TaskState.COMPLETED) > 0) {
            note(MigrationLogLevel.INFO, "full load finished, task completed");
            log.info("Task job {} full-only run finished, state COMPLETED", taskId);
        }
    }

    private void note(MigrationLogLevel level, String message) {
        logListener.emit(level, message);
    }
}
