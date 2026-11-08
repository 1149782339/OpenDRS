package io.opendrs.datacheck.job;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Runtime registry: at most one live coordinator per check task. MySQL is the source of truth. */
@Component
public class DataCheckCoordinatorRegistry {

    private final ConcurrentHashMap<Long, DataCheckCoordinator> jobs = new ConcurrentHashMap<>();

    public boolean tryRegister(DataCheckCoordinator job) {
        DataCheckCoordinator previous = jobs.putIfAbsent(job.taskId(), job);
        if (previous == null) {
            return true;
        }
        if (!previous.occupiesSlot()) {
            return jobs.replace(job.taskId(), previous, job);
        }
        return false;
    }

    public void requestStop(long taskId) {
        DataCheckCoordinator job = jobs.get(taskId);
        if (job != null) {
            job.requestStop();
        }
    }

    public void remove(long taskId, DataCheckCoordinator job) {
        jobs.remove(taskId, job);
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        jobs.values().forEach(DataCheckCoordinator::requestStop);
    }
}
