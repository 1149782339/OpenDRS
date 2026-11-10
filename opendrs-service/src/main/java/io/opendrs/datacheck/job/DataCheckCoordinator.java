package io.opendrs.datacheck.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendrs.datacheck.api.request.DataCheckOptions;
import io.opendrs.datacheck.domain.DataCheckMode;
import io.opendrs.datacheck.domain.DataCheckPhase;
import io.opendrs.datacheck.domain.DataCheckTableStatus;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.DataCheckTableRow;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.DataCheckTaskRow;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.EndpointRow;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.MigrationTaskRow;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.OffsetRow;
import io.opendrs.datacheck.service.EndpointConfigs;
import io.opendrs.engine.capture.transport.CdcTransportSpec;
import io.opendrs.engine.capture.transport.PipelineKafkaNames;
import io.opendrs.engine.common.log.MigrationLogListener;
import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.core.DataCheckParam;
import io.opendrs.engine.core.DataCheckTask;
import io.opendrs.engine.core.MigrationEngine;
import io.opendrs.engine.core.TaskState;
import io.opendrs.engine.datacheck.InconsistentRowDBStore;
import io.opendrs.engine.datacheck.InconsistentRowStore;
import io.opendrs.engine.datacheck.incremental.CheckedTablePair;
import io.opendrs.engine.datacheck.incremental.IncrementalDataCheckConfig;
import io.opendrs.engine.datacheck.incremental.IncrementalDataCheckRunner;
import io.opendrs.engine.datacheck.incremental.ReplayWatermark;
import io.opendrs.engine.datacheck.incremental.IncrementalEventSource;
import io.opendrs.engine.datacheck.incremental.KafkaConsumerGroupOffsets;
import io.opendrs.engine.datacheck.incremental.KafkaIncrementalEventSource;
import io.opendrs.engine.datacheck.incremental.ReplayOffsetSnapshot;
import io.opendrs.engine.jdbc.JdbcConnection;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import io.opendrs.engine.jdbc.JdbcConnectionFactory;
import io.opendrs.migration.config.OpenDrsTransportProperties;
import io.opendrs.migration.service.MigrationLagRegistry;
import io.opendrs.migration.domain.TaskPipeline;
import io.opendrs.migration.mapper.TaskPipelineMapper;
import java.time.Duration;
import org.apache.kafka.common.errors.WakeupException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Coordinator thread for one data-check task: owns the persisted state machine and starts one
 * child per table, each child calling the engine's single-table {@code datacheck} and awaiting its
 * handle. Table failures are recorded on the table's own row; the task fails only when the run
 * itself cannot proceed or after the run when at least one table failed.
 *
 * <p>Incremental modes ({@link DataCheckMode#FULL_AND_INCREMENTAL},
 * {@link DataCheckMode#INCREMENTAL_ONLY}) require {@link CdcTransportSpec#KAFKA}. After the full
 * pass (if any) the coordinator consumes the pipeline topic until {@link #requestStop()}.
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
    private final TaskPipelineMapper pipelineMapper;
    private final OpenDrsTransportProperties transportProperties;
    private final DataSource metadataDataSource;
    private final JdbcConnectionFactory connectionFactory;
    private final Duration waitTimeout;
    private final Duration waitPollInterval;
    private final MigrationLagRegistry lagRegistry;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, DataCheckTask> running = new ConcurrentHashMap<>();
    private volatile Thread worker;
    private volatile IncrementalEventSource incrementalSource;

    public DataCheckCoordinator(
            long taskId,
            DataCheckTaskRepository repository,
            MigrationEngine engine,
            DataCheckCoordinatorRegistry registry,
            ObjectMapper objectMapper,
            int concurrency,
            TaskPipelineMapper pipelineMapper,
            OpenDrsTransportProperties transportProperties,
            DataSource metadataDataSource,
            JdbcConnectionFactory connectionFactory,
            Duration waitTimeout,
            Duration waitPollInterval,
            MigrationLagRegistry lagRegistry) {
        this.taskId = taskId;
        this.repository = repository;
        this.engine = engine;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.concurrency = concurrency;
        this.pipelineMapper = pipelineMapper;
        this.transportProperties = transportProperties;
        this.metadataDataSource = metadataDataSource;
        this.connectionFactory = connectionFactory;
        this.waitTimeout = waitTimeout == null ? Duration.ofMinutes(5) : waitTimeout;
        this.waitPollInterval = waitPollInterval == null ? Duration.ofSeconds(1) : waitPollInterval;
        this.lagRegistry = lagRegistry;
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
        IncrementalEventSource source = incrementalSource;
        if (source != null) {
            source.wakeup();
        }
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
            if (tables.isEmpty()) {
                repository.markFailed(taskId, "no tables to check");
                return;
            }

            MigrationTaskRow migrationTask = repository.findMigrationTask(task.migrationTaskId());
            if (migrationTask == null) {
                repository.markFailed(taskId, "migration task " + task.migrationTaskId() + " not found");
                return;
            }
            String kafkaError = incrementalRequiresKafka(task.mode(), resolveCdcTransportSpec(migrationTask));
            if (kafkaError != null) {
                failAll(tables, kafkaError);
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

            IncrementalContext incremental = null;
            if (task.mode() != DataCheckMode.FULL_ONLY) {
                incremental = prepareIncremental(task, migrationTask);
                if (incremental == null) {
                    return;
                }
            }

            if (task.mode() != DataCheckMode.INCREMENTAL_ONLY) {
                List<DataCheckTableRow> fullTables = tables.stream()
                        .filter(DataCheckRecovery::needsFullCheck)
                        .toList();
                if (task.mode() == DataCheckMode.FULL_ONLY || !fullTables.isEmpty()) {
                    runFullChecks(fullTables, source, target, includeFloat);
                    if (stopRequested.get()) {
                        finish(task.mode());
                        return;
                    }
                    if (task.mode() == DataCheckMode.FULL_ONLY) {
                        finish(task.mode());
                        return;
                    }
                }
            }

            List<DataCheckTableRow> incrementalTables = incrementalTables(task.mode());
            if (incrementalTables.isEmpty()) {
                finish(task.mode());
                return;
            }
            runIncremental(task, incremental, incrementalTables, source, target);
            finish(task.mode());
        } catch (RuntimeException ex) {
            if (stopRequested.get() && isWakeup(ex)) {
                DataCheckTaskRow current = repository.findTask(taskId);
                finish(current == null ? DataCheckMode.FULL_ONLY : current.mode());
                return;
            }
            log.error("Data check task {} failed", taskId, ex);
            repository.markFailed(taskId, message(ex));
        } finally {
            running.clear();
            incrementalSource = null;
            registry.remove(taskId, this);
        }
    }

    /**
     * Same priority as {@code MigrationCoordinator}: task {@code options.cdcTransportSpec}
     * overrides the process-wide {@code opendrs.transport.channel}, then DIRECT.
     */
    CdcTransportSpec resolveCdcTransportSpec(MigrationTaskRow migrationTask) {
        CdcTransportSpec processDefault = transportProperties != null
                ? transportProperties.channelEnum()
                : CdcTransportSpec.DIRECT;
        String optionsJson = migrationTask == null ? null : migrationTask.optionsJson();
        return resolveCdcTransportSpec(objectMapper, optionsJson, processDefault);
    }

    static CdcTransportSpec resolveCdcTransportSpec(
            ObjectMapper mapper, String optionsJson, CdcTransportSpec processDefault) {
        CdcTransportSpec fallback = processDefault != null ? processDefault : CdcTransportSpec.DIRECT;
        if (optionsJson == null || optionsJson.isBlank() || mapper == null) {
            return fallback;
        }
        try {
            var node = mapper.readTree(optionsJson);
            var spec = node.get("cdcTransportSpec");
            if (spec != null && spec.isTextual() && !spec.asText().isBlank()) {
                return CdcTransportSpec.valueOf(spec.asText());
            }
        } catch (Exception e) {
            // Invalid or unexpected options JSON: inherit the process-wide default.
        }
        return fallback;
    }

    /**
     * Incremental modes require the KAFKA transport. Returns an error message, or {@code null} if
     * the combination is allowed.
     */

    static String incrementalRequiresKafka(DataCheckMode mode, CdcTransportSpec channel) {
        if (mode == DataCheckMode.FULL_ONLY) {
            return null;
        }
        if (channel != CdcTransportSpec.KAFKA) {
            return "incremental data check requires KAFKA transport; current channel is " + channel;
        }
        return null;
    }

    private IncrementalContext prepareIncremental(DataCheckTaskRow task, MigrationTaskRow migrationTask) {
        TaskPipeline pipeline = resolvePipeline(task, migrationTask);
        if (pipeline == null || pipeline.getPublicId() == null || pipeline.getPublicId().isBlank()) {
            repository.markFailed(taskId, "migration pipeline not found or has no public_id");
            return null;
        }
        String bootstrap = transportProperties.getKafkaBootstrapServers();
        if (bootstrap == null || bootstrap.isBlank()) {
            repository.markFailed(taskId, "KAFKA transport requires opendrs.transport.kafka-bootstrap-servers");
            return null;
        }
        String topic = PipelineKafkaNames.topic(pipeline.getPublicId());
        String replayGroup = PipelineKafkaNames.consumerGroup(pipeline.getPublicId());
        Map<TopicPartition, Long> start = loadOrSnapshotOffsets(task, topic, replayGroup, bootstrap);
        if (start == null || start.isEmpty()) {
            repository.markFailed(taskId, "could not resolve Kafka offsets for topic " + topic);
            return null;
        }
        return new IncrementalContext(pipeline.getPublicId(), bootstrap, topic, replayGroup, start, pipeline.getId());
    }

    private Map<TopicPartition, Long> loadOrSnapshotOffsets(
            DataCheckTaskRow task, String topic, String replayGroup, String bootstrap) {
        List<OffsetRow> existing = repository.findOffsets(taskId);
        if (!existing.isEmpty()) {
            Map<TopicPartition, Long> resume = new LinkedHashMap<>();
            for (OffsetRow row : existing) {
                long offset = row.consumedOffset() != null ? row.consumedOffset() : row.startOffset();
                resume.put(new TopicPartition(row.topic(), row.partition()), offset);
            }
            return resume;
        }
        try (KafkaConsumerGroupOffsets offsets = new KafkaConsumerGroupOffsets(bootstrap)) {
            Map<TopicPartition, Long> start = ReplayOffsetSnapshot.snapshot(offsets, topic, replayGroup);
            for (Map.Entry<TopicPartition, Long> entry : start.entrySet()) {
                repository.ensureStartOffsets(
                        taskId, entry.getKey().topic(), entry.getKey().partition(), entry.getValue());
            }
            return start;
        }
    }

    private TaskPipeline resolvePipeline(DataCheckTaskRow task, MigrationTaskRow migrationTask) {
        if (task.pipelineId() != null) {
            return pipelineMapper.findById(task.pipelineId());
        }
        List<TaskPipeline> pipelines = pipelineMapper.findByTaskId(task.migrationTaskId());
        if (pipelines == null || pipelines.isEmpty()) {
            return null;
        }
        return pipelines.stream()
                .filter(pipeline -> pipeline.getSourceEndpointId().equals(migrationTask.sourceEndpointId())
                        && pipeline.getTargetEndpointId().equals(migrationTask.targetEndpointId()))
                .findFirst()
                .orElse(pipelines.get(0));
    }

    private void runFullChecks(
            List<DataCheckTableRow> tables,
            JdbcConnectionConfig source,
            JdbcConnectionConfig target,
            boolean includeFloat) {
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
    }

    private List<DataCheckTableRow> incrementalTables(DataCheckMode mode) {
        List<DataCheckTableRow> current = repository.findTableRows(taskId);
        List<DataCheckTableRow> selected = new ArrayList<>();
        for (DataCheckTableRow table : current) {
            if (eligibleForIncremental(mode, table)) {
                selected.add(table);
            }
        }
        return selected;
    }

    /**
     * After a successful full pass the table is {@code COMPLETED}. After the incremental
     * loop has started (including stop / a wakeup-failed run) the phase is already
     * {@code INCREMENTAL} and must still be resumed.
     */
    static boolean eligibleForIncremental(DataCheckMode mode, DataCheckTableRow table) {
        if (table == null || table.status() == DataCheckTableStatus.FAILED) {
            return false;
        }
        if (mode == DataCheckMode.INCREMENTAL_ONLY) {
            return true;
        }
        return table.phase() == DataCheckPhase.INCREMENTAL
                || table.status() == DataCheckTableStatus.COMPLETED;
    }

    private void runIncremental(
            DataCheckTaskRow task,
            IncrementalContext incremental,
            List<DataCheckTableRow> tables,
            JdbcConnectionConfig source,
            JdbcConnectionConfig target) {
        for (DataCheckTableRow table : tables) {
            repository.markTableIncrementalRunning(taskId, table.sourceTableId());
        }
        List<CheckedTablePair> pairs = new ArrayList<>(tables.size());
        for (DataCheckTableRow table : tables) {
            pairs.add(new CheckedTablePair(tableId(table.sourceTableId()), tableId(table.targetTableId())));
        }
        IncrementalDataCheckConfig config = new IncrementalDataCheckConfig(
                incremental.topic, incremental.replayGroup, waitTimeout, waitPollInterval);
        try (KafkaConsumerGroupOffsets offsets = new KafkaConsumerGroupOffsets(incremental.bootstrap);
                KafkaIncrementalEventSource events =
                        new KafkaIncrementalEventSource(incremental.bootstrap, task.publicId())) {
            incrementalSource = events;
            InconsistentRowStore store = new InconsistentRowDBStore(
                    () -> new JdbcConnection(
                            DatabaseType.MYSQL, null, cfg -> metadataDataSource.getConnection()),
                    taskId);
            ReplayWatermark watermark = lagRegistry == null
                    ? ReplayWatermark.NONE
                    : new RegistryReplayWatermark(lagRegistry, incremental.pipelineId(), incremental.topic());
            IncrementalDataCheckRunner runner = new IncrementalDataCheckRunner(
                    events, offsets, config, connectionFactory, watermark);
            TaskLagSink lagSink = new TaskLagSink();
            runner.run(
                    source,
                    target,
                    pairs,
                    incremental.startOffsets,
                    store,
                    next -> persistConsumed(next),
                    lagSink,
                    tableLogListener(),
                    stopRequested::get);
            for (DataCheckTableRow table : tables) {
                DataCheckTableStatus status = stopRequested.get()
                        ? DataCheckTableStatus.STOPPED
                        : DataCheckTableStatus.COMPLETED;
                repository.markTableFinished(taskId, table.sourceTableId(), status, null);
            }
        } finally {
            incrementalSource = null;
        }
    }

    private void persistConsumed(Map<TopicPartition, Long> nextOffsets) {
        for (Map.Entry<TopicPartition, Long> entry : nextOffsets.entrySet()) {
            repository.saveConsumedOffset(
                    taskId, entry.getKey().topic(), entry.getKey().partition(), entry.getValue());
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
                    includeFloat, tableLogListener(),
                    (checked, rows) -> repository.updateTableRowsChecked(
                            taskId, table.sourceTableId(), rows));
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

    /** True when every table has already entered the incremental phase (stop/resume). */
    static boolean alreadyInIncremental(List<DataCheckTableRow> tables) {
        return tables != null
                && !tables.isEmpty()
                && tables.stream().allMatch(table -> table.phase() == DataCheckPhase.INCREMENTAL);
    }

    static boolean isWakeup(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof WakeupException) {
                return true;
            }
        }
        return false;
    }

    private void finish(DataCheckMode mode) {
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
        if (mode == DataCheckMode.FULL_AND_INCREMENTAL || mode == DataCheckMode.INCREMENTAL_ONLY) {
            // Incremental runs until manual stop. A sibling table failing does not fail the task
            // while any incremental table is still eligible; if nothing remains, land based on
            // whether every remaining table failed.
            if (task.state() == TaskState.RUNNING) {
                List<DataCheckTableRow> current = repository.findTableRows(taskId);
                boolean resumable = current.stream()
                        .anyMatch(table -> eligibleForIncremental(mode, table)
                                && table.status() != DataCheckTableStatus.COMPLETED);
                if (resumable) {
                    return;
                }
                long failed = current.stream()
                        .filter(table -> table.status() == DataCheckTableStatus.FAILED)
                        .count();
                long completed = current.stream()
                        .filter(table -> table.status() == DataCheckTableStatus.COMPLETED)
                        .count();
                if (failed > 0 && completed == 0) {
                    repository.markFailed(taskId, failed + " table(s) failed");
                } else if (completed > 0 && failed == 0) {
                    repository.landCompleted(taskId);
                } else if (failed > 0) {
                    // Mixed: some tables finished, some failed — keep the task failed only when
                    // there is no successful incremental work; otherwise complete with errors
                    // recorded per table.
                    repository.landCompleted(taskId);
                }
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

    private void failAll(List<DataCheckTableRow> tables, String error) {
        for (DataCheckTableRow table : tables) {
            repository.markTableFinished(
                    taskId, table.sourceTableId(), DataCheckTableStatus.FAILED, error);
        }
        repository.markFailed(taskId, error);
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


    private MigrationLogListener tableLogListener() {
        return (level, message) -> {
            if (message == null || message.isBlank()) {
                return;
            }
            switch (level) {
                case ERROR -> log.error("{}", message);
                case WARNING -> log.warn("{}", message);
                default -> log.info("{}", message);
            }
        };
    }

    private final class TaskLagSink implements io.opendrs.engine.datacheck.incremental.LagSink {
        private long maxLag;
        private boolean any;

        @Override
        public void update(io.opendrs.engine.common.metadata.TableId sourceTable, long lagMs) {
            update(sourceTable, lagMs, false);
        }

        @Override
        public void update(
                io.opendrs.engine.common.metadata.TableId sourceTable,
                long lagMs,
                boolean waitingForReplay) {
            repository.updateTableLag(taskId, sourceTable.identifier(), lagMs, waitingForReplay);
            if (!any || lagMs > maxLag) {
                maxLag = lagMs;
            }
            any = true;
        }

        @Override
        public void flush() {
            if (any) {
                repository.updateTaskLag(taskId, maxLag);
            }
            maxLag = 0L;
            any = false;
        }
    }

    private record IncrementalContext(
            String pipelinePublicId,
            String bootstrap,
            String topic,
            String replayGroup,
            Map<TopicPartition, Long> startOffsets,
            long pipelineId) {
    }
}
