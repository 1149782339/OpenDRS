package io.opendrs.datacheck.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendrs.datacheck.api.request.DataCheckOptions;
import io.opendrs.datacheck.domain.DataCheckMode;
import io.opendrs.datacheck.domain.DataCheckTableStatus;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.DataCheckTableRow;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.DataCheckTaskRow;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.EndpointRow;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.MigrationTaskRow;
import io.opendrs.datacheck.service.EndpointConfigs;
import io.opendrs.engine.common.log.MigrationLogListener;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.core.DataCheckParam;
import io.opendrs.engine.core.DataCheckTask;
import io.opendrs.engine.core.MigrationEngine;
import io.opendrs.engine.core.TaskState;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinator thread for one data-check task: owns the persisted state machine and starts one
 * child per table, each child calling the engine's single-table {@code datacheck} and awaiting its
 * handle. Table failures are recorded on the table's own row; the task fails only when the run
 * itself cannot proceed or after the run when at least one table failed.
 */
public class DataCheckCoordinator implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(DataCheckCoordinator.class);
    private static final int ERROR_MESSAGE_MAX = 1024;

    private final long taskId;
    private final DataCheckTaskRepository repository;
    private final MigrationEngine engine;
    private final DataCheckCoordinatorRegistry registry;
    private final ObjectMapper objectMapper;
    private final int concurrency;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, DataCheckTask> running = new ConcurrentHashMap<>();
    private volatile Thread worker;

    public DataCheckCoordinator(
            long taskId,
            DataCheckTaskRepository repository,
            MigrationEngine engine,
            DataCheckCoordinatorRegistry registry,
            ObjectMapper objectMapper,
            int concurrency) {
        this.taskId = taskId;
        this.repository = repository;
        this.engine = engine;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.concurrency = concurrency;
    }

    public long taskId() {
        return taskId;
    }

    public boolean occupiesSlot() {
        Thread current = worker;
        return current == null || current.isAlive();
    }

    public void requestStop() {
        stopRequested.set(true);
        running.values().forEach(DataCheckTask::requestStop);
    }

    @Override
    public void run() {
        worker = Thread.currentThread();
        try {
            if (!claimRunning()) {
                return;
            }
            DataCheckTaskRow task = repository.findTask(taskId);
            if (task == null) {
                return;
            }
            List<DataCheckTableRow> tables = repository.findTableRows(taskId);
            if (task.mode() != DataCheckMode.FULL_ONLY) {
                failUnsupported(tables);
                return;
            }
            if (tables.isEmpty()) {
                repository.markFailed(taskId, "no tables to check");
                return;
            }

            MigrationTaskRow migrationTask = repository.findMigrationTask(task.migrationTaskId());
            if (migrationTask == null) {
                repository.markFailed(taskId, "migration task " + task.migrationTaskId() + " not found");
                return;
            }
            List<EndpointRow> endpoints = repository.findEndpoints(List.of(
                    migrationTask.sourceEndpointId(), migrationTask.targetEndpointId()));
            EndpointRow sourceEndpoint = endpoint(endpoints, migrationTask.sourceEndpointId());
            EndpointRow targetEndpoint = endpoint(endpoints, migrationTask.targetEndpointId());
            if (sourceEndpoint == null || targetEndpoint == null) {
                repository.markFailed(taskId, "source or target endpoint not found");
                return;
            }
            JdbcConnectionConfig source = EndpointConfigs.toConfig(sourceEndpoint, objectMapper);
            JdbcConnectionConfig target = EndpointConfigs.toConfig(targetEndpoint, objectMapper);
            boolean includeFloat = includeFloat(task.optionsJson());

            int threads = Math.max(1, Math.min(tables.size(), concurrency));
            ExecutorService tableExecutor = Executors.newFixedThreadPool(threads, runnable -> {
                Thread thread = new Thread(runnable, "datacheck-" + taskId + "-table");
                thread.setDaemon(true);
                return thread;
            });
            try {
                List<Future<?>> futures = new ArrayList<>(tables.size());
                for (DataCheckTableRow table : tables) {
                    futures.add(tableExecutor.submit(
                            () -> checkTable(table, source, target, includeFloat)));
                }
                for (Future<?> future : futures) {
                    if (stopRequested.get()) {
                        break;
                    }
                    try {
                        future.get();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (ExecutionException ignored) {
                        // every child records its own failure
                    }
                }
            } finally {
                tableExecutor.shutdownNow();
            }
            finish();
        } catch (RuntimeException ex) {
            log.error("Data check task {} failed", taskId, ex);
            repository.markFailed(taskId, message(ex));
        } finally {
            running.clear();
            registry.remove(taskId, this);
        }
    }

    private boolean claimRunning() {
        DataCheckTaskRow task = repository.findTask(taskId);
        if (task == null) {
            return false;
        }
        if (task.state() == TaskState.STARTING) {
            return repository.casState(taskId, TaskState.STARTING, TaskState.RUNNING) > 0;
        }
        return task.state() == TaskState.RUNNING;
    }

    private void checkTable(
            DataCheckTableRow table,
            JdbcConnectionConfig source,
            JdbcConnectionConfig target,
            boolean includeFloat) {
        if (stopRequested.get()) {
            repository.markTableFinished(
                    taskId, table.sourceTableId(), DataCheckTableStatus.STOPPED, null);
            return;
        }
        repository.markTableRunning(taskId, table.sourceTableId());
        try {
            DataCheckParam param = new DataCheckParam(
                    taskId, source, target,
                    tableId(table.sourceTableId()), tableId(table.targetTableId()),
                    includeFloat, MigrationLogListener.NONE);
            DataCheckTask handle = engine.datacheck(param);
            running.put(table.sourceTableId(), handle);
            if (stopRequested.get()) {
                handle.requestStop();
            }
            handle.await();
            if (handle.failure() != null) {
                repository.markTableFinished(
                        taskId, table.sourceTableId(), DataCheckTableStatus.FAILED,
                        message(handle.failure()));
            } else if (handle.state() == TaskState.STOPPED) {
                repository.markTableFinished(
                        taskId, table.sourceTableId(), DataCheckTableStatus.STOPPED, null);
            } else {
                repository.markTableFinished(
                        taskId, table.sourceTableId(), DataCheckTableStatus.COMPLETED, null);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            repository.markTableFinished(
                    taskId, table.sourceTableId(), DataCheckTableStatus.STOPPED, null);
        } catch (RuntimeException ex) {
            repository.markTableFinished(
                    taskId, table.sourceTableId(), DataCheckTableStatus.FAILED, message(ex));
        } finally {
            running.remove(table.sourceTableId());
        }
    }

    private void finish() {
        DataCheckTaskRow task = repository.findTask(taskId);
        if (task == null) {
            return;
        }
        if (stopRequested.get()) {
            if (task.state() == TaskState.TERMINATING) {
                repository.landTerminated(taskId);
            } else {
                repository.landStopped(taskId);
            }
            return;
        }
        long failed = repository.findTableRows(taskId).stream()
                .filter(table -> table.status() == DataCheckTableStatus.FAILED)
                .count();
        if (failed > 0) {
            repository.markFailed(taskId, failed + " table(s) failed");
        } else {
            repository.landCompleted(taskId);
        }
    }

    private void failUnsupported(List<DataCheckTableRow> tables) {
        for (DataCheckTableRow table : tables) {
            repository.markTableFinished(
                    taskId, table.sourceTableId(), DataCheckTableStatus.FAILED,
                    "incremental data check is not implemented yet");
        }
        repository.markFailed(taskId, "incremental data check is not implemented yet");
    }

    private boolean includeFloat(String optionsJson) {
        if (optionsJson == null || optionsJson.isBlank()) {
            return false;
        }
        try {
            DataCheckOptions options = objectMapper.readValue(optionsJson, DataCheckOptions.class);
            return options != null && options.includeFloat();
        } catch (Exception ex) {
            return false;
        }
    }

    private static EndpointRow endpoint(List<EndpointRow> endpoints, long id) {
        return endpoints.stream().filter(endpoint -> endpoint.id() == id).findFirst().orElse(null);
    }

    private static TableId tableId(String identifier) {
        int dot = identifier.indexOf('.');
        if (dot < 0) {
            return TableId.of(null, identifier);
        }
        return TableId.of(identifier.substring(0, dot), identifier.substring(dot + 1));
    }

    private static String message(Throwable failure) {
        StringBuilder text = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 10; depth++) {
            Throwable next = current.getCause() == current ? null : current.getCause();
            String currentMessage = current.getMessage();
            if (currentMessage != null && !currentMessage.isBlank()
                    && !currentMessage.equals(String.valueOf(next))
                    && text.indexOf(currentMessage) < 0) {
                if (!text.isEmpty()) {
                    text.append(" <- ");
                }
                text.append(currentMessage);
            }
            current = next;
        }
        if (text.isEmpty()) {
            text.append(failure.getClass().getSimpleName());
        }
        return text.length() <= ERROR_MESSAGE_MAX
                ? text.toString()
                : text.substring(0, ERROR_MESSAGE_MAX);
    }
}
