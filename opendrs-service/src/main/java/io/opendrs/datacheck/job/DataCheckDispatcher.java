package io.opendrs.datacheck.job;

import io.opendrs.datacheck.mapper.DataCheckTaskRepository;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.DataCheckTaskRow;
import io.opendrs.engine.core.TaskState;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * On boot, recovers data-check tasks left in a live state: full-phase tables fail, incremental
 * tables resume from {@code datacheck_offsets}. Also dispatches {@code STARTING}/{@code RUNNING}
 * tasks that have no live coordinator (so a task cannot stay stuck and unstartable).
 */
@Component
@Order(200)
public class DataCheckDispatcher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataCheckDispatcher.class);

    private final DataCheckTaskRepository repository;
    private final DataCheckCoordinatorRegistry registry;
    private final DataCheckCoordinatorFactory coordinatorFactory;
    private final ExecutorService coordinatorExecutor;

    public DataCheckDispatcher(
            DataCheckTaskRepository repository,
            DataCheckCoordinatorRegistry registry,
            DataCheckCoordinatorFactory coordinatorFactory,
            ExecutorService dataCheckCoordinatorExecutor) {
        this.repository = repository;
        this.registry = registry;
        this.coordinatorFactory = coordinatorFactory;
        this.coordinatorExecutor = dataCheckCoordinatorExecutor;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<DataCheckTaskRow> stuck = repository.findTasksInStates(List.of(
                TaskState.STARTING, TaskState.RUNNING, TaskState.STOPPING, TaskState.TERMINATING));
        int resumed = 0;
        int landed = 0;
        for (DataCheckTaskRow task : stuck) {
            if (registry.hasLive(task.id())) {
                continue;
            }
            DataCheckRecovery.Decision decision = DataCheckRecovery.recover(repository, task);
            if (decision == DataCheckRecovery.Decision.RESUME) {
                if (dispatch(task.id())) {
                    resumed++;
                }
            } else {
                landed++;
            }
        }
        if (resumed > 0 || landed > 0) {
            log.info("Recovered data-check tasks on boot: resumed={}, landed={}", resumed, landed);
        }
    }

    @Scheduled(fixedDelayString = "${opendrs.task.dispatch-ms:2000}")
    public void dispatchOrphaned() {
        List<DataCheckTaskRow> live = repository.findTasksInStates(
                List.of(TaskState.STARTING, TaskState.RUNNING));
        for (DataCheckTaskRow task : live) {
            if (registry.hasLive(task.id())) {
                continue;
            }
            // A STARTING/RUNNING row with no worker was left behind; recover then resume.
            DataCheckRecovery.Decision decision = DataCheckRecovery.recover(repository, task);
            if (decision == DataCheckRecovery.Decision.RESUME) {
                dispatch(task.id());
            }
        }
    }

    private boolean dispatch(long taskId) {
        DataCheckCoordinator coordinator = coordinatorFactory.create(taskId);
        if (!registry.tryRegister(coordinator)) {
            return false;
        }
        coordinatorExecutor.execute(coordinator);
        return true;
    }
}
