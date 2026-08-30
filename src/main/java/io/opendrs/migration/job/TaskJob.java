package io.opendrs.migration.job;

import io.opendrs.migration.domain.JobPhase;
import io.opendrs.migration.domain.JobState;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinator thread for one task. Cooperative stub: no dump / Debezium. Stop via {@link #requestStop()}.
 */
public class TaskJob implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TaskJob.class);

    private final Long taskId;
    private final MigrationMode mode;
    private final MigrationTaskMapper taskMapper;
    private final TaskJobRegistry registry;
    private final CountDownLatch incrementalHold = new CountDownLatch(1);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile Thread worker;

    public TaskJob(Long taskId, MigrationMode mode, MigrationTaskMapper taskMapper, TaskJobRegistry registry) {
        this.taskId = taskId;
        this.mode = mode;
        this.taskMapper = taskMapper;
        this.registry = registry;
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
        incrementalHold.countDown();
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

            if (task.getJobPhase() == JobPhase.PRECHECKED) {
                taskMapper.compareAndSetPhase(taskId, JobPhase.PRECHECKED, JobPhase.SCHEMA_SNAPSHOT);
                log.info("TODO first-round Debezium Engine (offset + schema history) for task {}", taskId);
            }
            if (stopRequested()) {
                finishStopped();
                return;
            }

            JobPhase phase = currentPhase();
            if ((mode == MigrationMode.FULL_AND_INCREMENTAL || mode == MigrationMode.FULL_ONLY)
                    && (phase == JobPhase.SCHEMA_SNAPSHOT || phase == JobPhase.PRECHECKED)) {
                taskMapper.updatePhase(taskId, JobPhase.FULL);
                log.info("TODO parallel full dump for task {}", taskId);
            }
            if (stopRequested()) {
                finishStopped();
                return;
            }

            if (mode == MigrationMode.FULL_AND_INCREMENTAL || mode == MigrationMode.INCREMENTAL_ONLY) {
                taskMapper.updatePhase(taskId, JobPhase.INCREMENTAL);
                while (!stopRequested()) {
                    incrementalHold.await();
                }
            }
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
            registry.remove(taskId, this);
        }
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
}
