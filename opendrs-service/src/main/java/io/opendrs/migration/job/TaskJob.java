package io.opendrs.migration.job;

import io.opendrs.debezium.CdcEngine;
import io.opendrs.debezium.CdcEngineFactory;
import io.opendrs.debezium.DebeziumEngineConfig;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.JobPhase;
import io.opendrs.migration.domain.JobState;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import io.opendrs.migration.service.TableSelectionExpander;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinator thread for one task. Starts a single Debezium Engine that snapshots schema+data then
 * streams CDC. Cooperative stop calls {@link CdcEngine#stop()} ({@code engine.stop()} /
 * {@code close()}).
 */
public class TaskJob implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TaskJob.class);

    private final Long taskId;
    private final MigrationMode mode;
    private final MigrationTaskMapper taskMapper;
    private final ConnectionInfoMapper connectionMapper;
    private final TaskJobRegistry registry;
    private final TableSelectionExpander tableExpander;
    private final CdcEngineFactory engineFactory;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Thread worker;
    private volatile CdcEngine runningEngine;

    public TaskJob(Long taskId, MigrationMode mode, MigrationTaskMapper taskMapper, TaskJobRegistry registry) {
        this(taskId, mode, taskMapper, null, registry, null, null);
    }

    public TaskJob(
            Long taskId,
            MigrationMode mode,
            MigrationTaskMapper taskMapper,
            ConnectionInfoMapper connectionMapper,
            TaskJobRegistry registry,
            TableSelectionExpander tableExpander,
            CdcEngineFactory engineFactory) {
        this.taskId = taskId;
        this.mode = mode;
        this.taskMapper = taskMapper;
        this.connectionMapper = connectionMapper;
        this.registry = registry;
        this.tableExpander = tableExpander;
        this.engineFactory = engineFactory;
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

    public void requestStop() {
        stopRequested.set(true);
        CdcEngine engine = runningEngine;
        if (engine != null) {
            engine.stop();
        }
        Thread current = worker;
        if (current != null) {
            current.interrupt();
        }
    }

    @Override
    public void run() {
        worker = Thread.currentThread();
        try {
            MigrationTask task = taskMapper.findById(taskId);
            if (task == null) {
                return;
            }
            if (task.getJobState() != JobState.STARTING && task.getJobState() != JobState.RUNNING) {
                return;
            }
            if (task.getJobState() == JobState.STARTING) {
                int updated = taskMapper.compareAndSetJobState(taskId, JobState.STARTING, JobState.RUNNING);
                if (updated == 0) {
                    return;
                }
            }
            if (stopRequested()) {
                finishStopped();
                return;
            }

            runCapture();
            finishStopped();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finishStopped();
            log.info("Task job {} interrupted", taskId);
        } catch (Exception ex) {
            log.error("Task job {} failed", taskId, ex);
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (message.length() > 1024) {
                message = message.substring(0, 1024);
            }
            taskMapper.markJobFailed(taskId, message);
        } finally {
            stopEngineQuietly();
            registry.remove(taskId, this);
        }
    }

    void runCapture() throws Exception {
        JobPhase phase = currentPhase();
        if (phase == JobPhase.PRECHECKED) {
            taskMapper.compareAndSetPhase(taskId, JobPhase.PRECHECKED, JobPhase.SCHEMA_SNAPSHOT);
            phase = JobPhase.SCHEMA_SNAPSHOT;
        }
        if (phase == JobPhase.FULL) {
            taskMapper.compareAndSetPhase(taskId, JobPhase.FULL, JobPhase.INCREMENTAL);
            phase = JobPhase.INCREMENTAL;
        }
        if (phase != JobPhase.SCHEMA_SNAPSHOT && phase != JobPhase.INCREMENTAL) {
            return;
        }
        if (stopRequested()) {
            return;
        }
        log.info("Task job {} mode {} starting one capture Engine", taskId, mode);
        CdcEngine engine = engineFactory.create(engineSpec(), this::onSnapshotCompleted);
        runningEngine = engine;
        try {
            if (stopRequested()) {
                engine.stop();
                return;
            }
            engine.run();
        } finally {
            runningEngine = null;
            stopQuietly(engine);
        }
    }

    private void onSnapshotCompleted() {
        JobPhase phase = currentPhase();
        if (phase == JobPhase.SCHEMA_SNAPSHOT) {
            taskMapper.compareAndSetPhase(taskId, JobPhase.SCHEMA_SNAPSHOT, JobPhase.INCREMENTAL);
        } else if (phase == JobPhase.FULL) {
            taskMapper.compareAndSetPhase(taskId, JobPhase.FULL, JobPhase.INCREMENTAL);
        }
    }

    private DebeziumEngineConfig.EngineSpec engineSpec() {
        MigrationTask task = taskMapper.findById(taskId);
        if (task == null) {
            throw new IllegalStateException("Task not found: " + taskId);
        }
        ConnectionInfo source = connectionMapper.findById(task.getSourceConnectionId());
        if (source == null) {
            throw new IllegalStateException("Source connection not found for task " + taskId);
        }
        List<Table> tables = tableExpander.expand(source, task.getTablesJson());
        if (tables.isEmpty()) {
            tables = tableExpander.expandExplicit(task.getTablesJson());
        }
        if (tables.isEmpty()) {
            throw new IllegalStateException("No tables expanded for task " + taskId);
        }
        ConnectionInfo target = null;
        if (task.getTargetConnectionId() != null) {
            target = connectionMapper.findById(task.getTargetConnectionId());
        }
        return DebeziumEngineConfig.EngineSpec.of(
                taskId, source, tables, task.getOptionsJson(), target, task.getTablesJson());
    }

    private JobPhase currentPhase() {
        MigrationTask task = taskMapper.findById(taskId);
        return task == null ? null : task.getJobPhase();
    }

    private boolean stopRequested() {
        return stopRequested.get() || Thread.currentThread().isInterrupted();
    }

    private void finishStopped() {
        MigrationTask task = taskMapper.findById(taskId);
        if (task == null) {
            return;
        }
        if (task.getJobState() == JobState.STOPPED || task.getJobState() == JobState.FAILED) {
            return;
        }
        if (task.getJobState() == JobState.STOPPING) {
            taskMapper.compareAndSetJobState(taskId, JobState.STOPPING, JobState.STOPPED);
            return;
        }
        if (task.getJobState() == JobState.RUNNING) {
            taskMapper.compareAndSetJobState(taskId, JobState.RUNNING, JobState.STOPPED);
        }
    }

    private void stopEngineQuietly() {
        CdcEngine engine = runningEngine;
        runningEngine = null;
        stopQuietly(engine);
    }

    private static void stopQuietly(CdcEngine engine) {
        if (engine == null) {
            return;
        }
        try {
            engine.stop();
        } catch (RuntimeException ex) {
            log.debug("Engine stop after run: {}", ex.getMessage());
        }
    }
}
