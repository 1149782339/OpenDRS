package io.opendrs.migration.job;

import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.TaskState;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinator thread for one task. v1 only stubs phases; no dump / Debezium / stop.
 */
public class TaskJob implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TaskJob.class);

    private final Long taskId;
    private final MigrationMode mode;
    private final MigrationTaskMapper taskMapper;
    private final TaskJobRegistry registry;
    private final CountDownLatch incrementalHold = new CountDownLatch(1);
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

    /** Process/context teardown only — not the public stop API. */
    void releaseHold() {
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
            taskMapper.updateState(taskId, TaskState.SCHEMA_SNAPSHOTTING);
            // TODO: 第一轮 Engine：写入 debezium_offset + debezium_schema_history（捕获 SCN/GTID）。
            log.info("TODO first-round Debezium Engine (offset + schema history) for task {}", taskId);

            if (mode == MigrationMode.FULL_AND_INCREMENTAL || mode == MigrationMode.FULL_ONLY) {
                taskMapper.updateState(taskId, TaskState.FULL);
                // TODO: 并行全量 dump（SELECT/INSERT）。
                log.info("TODO parallel full dump for task {}", taskId);
            }

            if (mode == MigrationMode.FULL_AND_INCREMENTAL || mode == MigrationMode.INCREMENTAL_ONLY) {
                taskMapper.updateState(taskId, TaskState.INCREMENTAL);
                // 增量占位：阻塞直到进程结束。stop 尚未接线，忽略 STOPPING。
                incrementalHold.await();
            } else {
                taskMapper.updateState(taskId, TaskState.STOPPED);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.info("Task job {} interrupted", taskId);
        } catch (Exception ex) {
            log.error("Task job {} failed", taskId, ex);
            String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            if (message.length() > 1024) {
                message = message.substring(0, 1024);
            }
            taskMapper.markFailed(taskId, message);
        } finally {
            registry.remove(taskId, this);
        }
    }
}
