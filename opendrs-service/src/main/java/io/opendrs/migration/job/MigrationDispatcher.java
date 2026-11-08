package io.opendrs.migration.job;

import io.opendrs.engine.core.TaskState;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Single-JVM dispatcher: attach a coordinator thread for rows in STARTING/RUNNING that have no live
 * registry entry. Re-reads job_state after putIfAbsent so a STARTING→STOPPED CAS is not overridden.
 */
@Component
public class MigrationDispatcher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationDispatcher.class);

    private final MigrationTaskMapper taskMapper;
    private final MigrationCoordinatorRegistry registry;
    private final MigrationCoordinatorFactory coordinatorFactory;
    private final ThreadPoolTaskExecutor coordinatorExecutor;

    public MigrationDispatcher(
            MigrationTaskMapper taskMapper,
            MigrationCoordinatorRegistry registry,
            MigrationCoordinatorFactory coordinatorFactory,
            ThreadPoolTaskExecutor coordinatorExecutor) {
        this.taskMapper = taskMapper;
        this.registry = registry;
        this.coordinatorFactory = coordinatorFactory;
        this.coordinatorExecutor = coordinatorExecutor;
    }

    @Override
    public void run(ApplicationArguments args) {
        int n = taskMapper.markStoppingAsStopped();
        if (n > 0) {
            log.info("Recovered {} wind-down task(s) (STOPPING→STOPPED / TERMINATING→TERMINATED) on boot", n);
        }
    }

    @Scheduled(fixedDelayString = "${opendrs.task.dispatch-ms:2000}")
    public void dispatch() {
        for (MigrationTask task : taskMapper.findDispatchable()) {
            if (registry.hasLive(task.getId())) {
                continue;
            }
            MigrationCoordinator job = coordinatorFactory.create(task);
            if (!registry.tryRegister(job)) {
                continue;
            }
            MigrationTask latest = taskMapper.findById(task.getId());
            if (latest == null || !isDispatchable(latest.getState())) {
                registry.remove(task.getId(), job);
                continue;
            }
            coordinatorExecutor.execute(job);
        }
    }

    static boolean isDispatchable(TaskState jobState) {
        return jobState == TaskState.STARTING || jobState == TaskState.RUNNING;
    }
}
