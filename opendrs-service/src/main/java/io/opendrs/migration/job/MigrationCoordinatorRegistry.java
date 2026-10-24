package io.opendrs.migration.job;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Runtime in-memory registry: at most one coordinator per taskId. MySQL is the source of truth.
 */
@Component
public class MigrationCoordinatorRegistry {

    private final ConcurrentHashMap<Long, MigrationCoordinator> jobs = new ConcurrentHashMap<>();

    public boolean hasLive(Long taskId) {
        MigrationCoordinator job = jobs.get(taskId);
        return job != null && job.occupiesSlot();
    }

    public boolean tryRegister(MigrationCoordinator job) {
        MigrationCoordinator previous = jobs.putIfAbsent(job.getTaskId(), job);
        if (previous == null) {
            return true;
        }
        if (!previous.occupiesSlot()) {
            return jobs.replace(job.getTaskId(), previous, job);
        }
        return false;
    }

    public void requestStop(Long taskId) {
        MigrationCoordinator job = jobs.get(taskId);
        if (job != null) {
            job.requestStop();
        }
    }

    public void remove(Long taskId, MigrationCoordinator job) {
        jobs.remove(taskId, job);
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        jobs.values().forEach(MigrationCoordinator::requestStop);
    }
}
