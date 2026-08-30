package io.opendrs.migration.job;

import io.opendrs.migration.domain.JobState;
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
public class JobDispatcher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);

    private final MigrationTaskMapper taskMapper;
    private final TaskJobRegistry registry;
    private final TaskJobFactory taskJobFactory;
    private final ThreadPoolTaskExecutor taskJobExecutor;

    public JobDispatcher(
            MigrationTaskMapper taskMapper,
            TaskJobRegistry registry,
            TaskJobFactory taskJobFactory,
            ThreadPoolTaskExecutor taskJobExecutor) {
        this.taskMapper = taskMapper;
        this.registry = registry;
        this.taskJobFactory = taskJobFactory;
        this.taskJobExecutor = taskJobExecutor;
    }

    @Override
    public void run(ApplicationArguments args) {
        int n = taskMapper.markStoppingAsStopped();
        if (n > 0) {
            log.info("Recovered {} STOPPING task(s) to STOPPED on boot", n);
        }
    }

    @Scheduled(fixedDelayString = "${opendrs.job.dispatch-ms:2000}")
    public void dispatch() {
        for (MigrationTask task : taskMapper.findDispatchable()) {
            if (registry.hasLive(task.getId())) {
                continue;
            }
            TaskJob job = taskJobFactory.create(task);
            if (!registry.tryRegister(job)) {
                continue;
            }
            MigrationTask latest = taskMapper.findById(task.getId());
            if (latest == null || !isDispatchable(latest.getJobState())) {
                registry.remove(task.getId(), job);
                continue;
            }
            taskJobExecutor.execute(job);
        }
    }

    static boolean isDispatchable(JobState jobState) {
        return jobState == JobState.STARTING || jobState == JobState.RUNNING;
    }
}
