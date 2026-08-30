package io.opendrs.migration.job;

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * 运行时内存注册表：每个 taskId 至多一个 coordinator。MySQL 才是状态权威来源。
 */
@Component
public class TaskJobRegistry {

    private final ConcurrentHashMap<Long, TaskJob> jobs = new ConcurrentHashMap<>();
    private final ThreadPoolTaskExecutor taskJobExecutor;
    private final MigrationTaskMapper taskMapper;

    public TaskJobRegistry(ThreadPoolTaskExecutor taskJobExecutor, MigrationTaskMapper taskMapper) {
        this.taskJobExecutor = taskJobExecutor;
        this.taskMapper = taskMapper;
    }

    public void start(MigrationTask task) {
        TaskJob job = new TaskJob(task.getId(), task.getMode(), taskMapper, this);
        TaskJob previous = jobs.putIfAbsent(task.getId(), job);
        if (previous != null && previous.isRunning()) {
            throw AppException.of(
                    ErrorCode.TASK_CONFLICT,
                    "Task " + task.getId() + " already has a running job");
        }
        if (previous != null) {
            jobs.put(task.getId(), job);
        }
        taskJobExecutor.execute(job);
    }

    void remove(Long taskId, TaskJob job) {
        jobs.remove(taskId, job);
    }

    @PreDestroy
    public void shutdown() {
        jobs.values().forEach(TaskJob::releaseHold);
        taskJobExecutor.shutdown();
        taskJobExecutor.getThreadPoolExecutor().shutdownNow();
    }
}
