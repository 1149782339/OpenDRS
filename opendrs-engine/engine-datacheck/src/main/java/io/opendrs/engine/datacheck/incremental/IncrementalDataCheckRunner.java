package io.opendrs.engine.datacheck.incremental;

import io.opendrs.engine.capture.transport.SourceRecordEnvelopeCodec;
import io.opendrs.engine.common.log.MigrationLogLevel;
import io.opendrs.engine.common.log.MigrationLogListener;
import io.opendrs.engine.common.metadata.ColumnDefinition;
import io.opendrs.engine.common.metadata.TableDefinition;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.common.naming.ColumnNamingStrategy;
import io.opendrs.engine.common.naming.TableNamingStrategy;
import io.opendrs.engine.datacheck.InconsistentRowStore;
import io.opendrs.engine.datacheck.PrimaryKeySupport;
import io.opendrs.engine.jdbc.JdbcConnection;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import io.opendrs.engine.jdbc.JdbcConnectionFactory;
import io.opendrs.engine.replay.applier.SourceRecordConverter;
import io.opendrs.engine.replay.event.ChangeEvent;
import io.opendrs.engine.replay.event.DataChangeEvent;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Incremental check: consume the pipeline topic from a recorded offset, re-query changed PKs on
 * both sides, and update the inconsistent-row store until {@code stopRequested}.
 */
public final class IncrementalDataCheckRunner {

    private static final Logger log = LoggerFactory.getLogger(IncrementalDataCheckRunner.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);

    private static final TableNamingStrategy SOURCE_TABLES = tableId -> {
        String namespace = tableId.getSchema() != null && !tableId.getSchema().isBlank()
                ? tableId.getSchema()
                : tableId.getCatalog();
        return TableId.of(namespace, tableId.getTable());
    };
    private static final ColumnNamingStrategy SOURCE_COLUMNS = column -> column;

    private final IncrementalEventSource events;
    private final ConsumerGroupOffsets offsets;
    private final IncrementalDataCheckConfig config;
    private final JdbcConnectionFactory factory;
    private final SourceRecordEnvelopeCodec codec;
    private final SourceRecordConverter converter;
    private final PkBatchChecker checkerOverride;
    private final ReplayWatermark replayWatermark;
    private long lastConsumedCreateTimeMs;
    private final Map<TopicPartition, Long> lastConsumedNext = new HashMap<>();
    private final Map<TableId, Long> lastTableCreateTimeMs = new HashMap<>();

    public IncrementalDataCheckRunner(
            IncrementalEventSource events,
            ConsumerGroupOffsets offsets,
            IncrementalDataCheckConfig config,
            JdbcConnectionFactory factory) {
        this(events, offsets, config, factory, new SourceRecordEnvelopeCodec(),
                new SourceRecordConverter(SOURCE_TABLES, SOURCE_COLUMNS, false), null,
                ReplayWatermark.NONE);
    }

    public IncrementalDataCheckRunner(
            IncrementalEventSource events,
            ConsumerGroupOffsets offsets,
            IncrementalDataCheckConfig config,
            JdbcConnectionFactory factory,
            ReplayWatermark replayWatermark) {
        this(events, offsets, config, factory, new SourceRecordEnvelopeCodec(),
                new SourceRecordConverter(SOURCE_TABLES, SOURCE_COLUMNS, false), null,
                replayWatermark);
    }

    IncrementalDataCheckRunner(
            IncrementalEventSource events,
            ConsumerGroupOffsets offsets,
            IncrementalDataCheckConfig config,
            JdbcConnectionFactory factory,
            SourceRecordEnvelopeCodec codec,
            SourceRecordConverter converter,
            PkBatchChecker checkerOverride) {
        this(events, offsets, config, factory, codec, converter, checkerOverride, ReplayWatermark.NONE);
    }

    IncrementalDataCheckRunner(
            IncrementalEventSource events,
            ConsumerGroupOffsets offsets,
            IncrementalDataCheckConfig config,
            JdbcConnectionFactory factory,
            SourceRecordEnvelopeCodec codec,
            SourceRecordConverter converter,
            PkBatchChecker checkerOverride,
            ReplayWatermark replayWatermark) {
        this.events = events;
        this.offsets = offsets;
        this.config = config;
        this.factory = factory;
        this.codec = codec;
        this.converter = converter;
        this.checkerOverride = checkerOverride;
        this.replayWatermark = replayWatermark == null ? ReplayWatermark.NONE : replayWatermark;
    }

    public void run(
            JdbcConnectionConfig sourceConfig,
            JdbcConnectionConfig targetConfig,
            List<CheckedTablePair> tables,
            Map<TopicPartition, Long> startOffsets,
            InconsistentRowStore store,
            OffsetStore offsetStore,
            LagSink lagSink,
            MigrationLogListener logListener,
            BooleanSupplier stopRequested) {
        Objects.requireNonNull(tables, "tables");
        Objects.requireNonNull(startOffsets, "startOffsets");
        MigrationLogListener logs = logListener == null ? MigrationLogListener.NONE : logListener;
        Map<TableId, CheckedTablePair> bySource = index(tables);
        ReplayOffsetGate gate = new ReplayOffsetGate(
                offsets, config.replayGroup(), config.waitTimeout(), config.waitPollInterval());

        events.assignAndSeek(startOffsets);
        EventBatch pending = null;
        JdbcConnection source = null;
        JdbcConnection target = null;
        Map<TableId, TableDefinition> sourceDefs = new HashMap<>();
        Map<TableId, TableDefinition> targetDefs = new HashMap<>();
        PkBatchChecker checker = checkerOverride;
        try {
            if (checker == null && factory != null) {
                source = factory.open(sourceConfig);
                target = factory.open(targetConfig);
                loadDefinitions(tables, source, target, sourceDefs, targetDefs);
                checker = new JdbcPkBatchChecker(source, target, sourceDefs::get, targetDefs::get);
            }
            while (!stopRequested.getAsBoolean()) {
                if (pending == null) {
                    List<ConsumerRecord<String, byte[]>> records;
                    try {
                        records = events.poll(POLL_TIMEOUT);
                    } catch (WakeupException ex) {
                        // requestStop() calls wakeup() to interrupt a blocking poll.
                        break;
                    }
                    if (records == null || records.isEmpty()) {
                        reportIdleLag(lagSink, bySource.keySet());
                        continue;
                    }
                    pending = decode(records, bySource.keySet(), sourceDefs, logs);
                    if (pending.isEmpty()) {
                        pending = null;
                        continue;
                    }
                }
                if (pending.rowsByTable().isEmpty()) {
                    // only skipped events: no target wait, just advance
                    commit(pending, offsetStore);
                    pending = null;
                    continue;
                }
                EventBatch waiting = pending;
                ReplayOffsetGate.Result wait = gate.await(
                        waiting.maxEventOffsets(),
                        stopRequested,
                        (committed, elapsedMs, ready) -> publishLag(
                                lagSink,
                                waiting.rowsByTable().keySet(),
                                !ready,
                                waiting.maxEventOffsets(),
                                committed,
                                waiting.maxCreateTimeMs()));
                if (stopRequested.getAsBoolean()) {
                    break;
                }
                if (!wait.ready()) {
                    logs.emit(MigrationLogLevel.WARNING,
                            "incremental data check waiting for replay: " + wait.detail()
                                    + " lagMs=" + wait.lagMs());
                    continue;
                }
                for (Map.Entry<TableId, List<PkRow>> entry : pending.rowsByTable().entrySet()) {
                    if (stopRequested.getAsBoolean()) {
                        break;
                    }
                    CheckedTablePair pair = bySource.get(entry.getKey());
                    if (pair == null) {
                        continue;
                    }
                    CheckerHold hold = checkWithRetry(
                            checker, pair, entry.getValue(), store, logs,
                            source, target, sourceConfig, targetConfig, tables, sourceDefs, targetDefs);
                    source = hold.source;
                    target = hold.target;
                    checker = hold.checker;
                }
                if (stopRequested.getAsBoolean()) {
                    break;
                }
                publishLag(
                        lagSink,
                        waiting.rowsByTable().keySet(),
                        false,
                        null,
                        Map.of(),
                        waiting.maxCreateTimeMs() > 0L ? waiting.maxCreateTimeMs() : lastConsumedCreateTimeMs);
                commit(pending, offsetStore);
                pending = null;
            }
        } finally {
            closeQuietly(source);
            closeQuietly(target);
        }
    }

    private EventBatch decode(
            List<ConsumerRecord<String, byte[]>> records,
            Set<TableId> checked,
            Map<TableId, TableDefinition> sourceDefs,
            MigrationLogListener logs) {
        EventBatch batch = new EventBatch();
        for (ConsumerRecord<String, byte[]> record : records) {
            TopicPartition partition = new TopicPartition(record.topic(), record.partition());
            batch.noteRecord(partition, record.offset(), record.timestamp());
            if (record.timestamp() > lastConsumedCreateTimeMs) {
                lastConsumedCreateTimeMs = record.timestamp();
            }
            lastConsumedNext.put(partition, record.offset() + 1L);
            SourceRecord sourceRecord;
            try {
                sourceRecord = codec.deserialize(record.value());
            } catch (RuntimeException ex) {
                logs.emit(MigrationLogLevel.WARNING,
                        "skip unreadable CDC envelope at " + partition + "@" + record.offset()
                                + ": " + ex.getMessage());
                continue;
            }
            Optional<ChangeEvent> converted = converter.convertOne(sourceRecord);
            if (converted.isEmpty() || !(converted.get() instanceof DataChangeEvent change)) {
                continue;
            }
            TableId table = change.getTableId();
            if (table == null || !contains(checked, table)) {
                continue;
            }
            if (change.getFieldsMetaData() == null
                    || change.getFieldsMetaData().getPrimaryKeyFieldNames().isEmpty()) {
                logs.emit(MigrationLogLevel.WARNING,
                        "skip change without primary key for " + table);
                continue;
            }
            TableId canonical = lookup(checked, table);
            TableDefinition definition = sourceDefs == null ? null : sourceDefs.get(canonical);
            List<Object> raw = PrimaryKeyEncoder.bindValues(change);
            List<Object> bind;
            String encoded;
            if (definition != null) {
                List<ColumnDefinition> pkColumns = PrimaryKeySupport.primaryKeyColumns(definition);
                bind = PrimaryKeySupport.bindValues(canonical, pkColumns, raw);
                encoded = PrimaryKeySupport.encode(canonical, pkColumns, raw);
            } else {
                bind = raw;
                encoded = PrimaryKeyEncoder.encode(bind);
            }
            if (record.timestamp() > lastTableCreateTimeMs.getOrDefault(canonical, 0L)) {
                lastTableCreateTimeMs.put(canonical, record.timestamp());
            }
            batch.put(canonical, new PkRow(encoded, bind), record.timestamp());
        }
        return batch;
    }

    private static Map<TableId, CheckedTablePair> index(List<CheckedTablePair> tables) {
        Map<TableId, CheckedTablePair> map = new HashMap<>();
        for (CheckedTablePair pair : tables) {
            map.put(pair.source(), pair);
        }
        return map;
    }

    private static boolean contains(Set<TableId> tables, TableId table) {
        return tables.contains(table);
    }

    private static TableId lookup(Set<TableId> tables, TableId table) {
        return tables.contains(table) ? table : null;
    }

    private void commit(EventBatch batch, OffsetStore offsetStore) {
        Map<TopicPartition, Long> next = batch.nextOffsets();
        offsetStore.saveConsumed(next);
        events.commit(next);
    }

    private void reportIdleLag(LagSink lagSink, Set<TableId> tables) {
        try {
            List<TopicPartition> partitions = offsets.partitions(config.topic());
            Map<TopicPartition, Long> end = partitions.isEmpty()
                    ? Map.of()
                    : offsets.endOffsets(partitions);
            Map<TopicPartition, Long> committed = partitions.isEmpty()
                    ? Map.of()
                    : offsets.committed(config.replayGroup(), partitions);
            publishLag(lagSink, tables, false, null, committed, lastConsumedCreateTimeMs, end);
        } catch (RuntimeException ex) {
            log.debug("idle lag check failed: {}", ex.toString());
        }
    }

    private void publishLag(
            LagSink lagSink,
            Set<TableId> tables,
            boolean waitingForTarget,
            Map<TopicPartition, Long> requiredEventOffsets,
            Map<TopicPartition, Long> groupCommitted,
            long consumedCreateTimeMs) {
        publishLag(lagSink, tables, waitingForTarget, requiredEventOffsets, groupCommitted,
                consumedCreateTimeMs, null);
    }

    private void publishLag(
            LagSink lagSink,
            Set<TableId> tables,
            boolean waitingForTarget,
            Map<TopicPartition, Long> requiredEventOffsets,
            Map<TopicPartition, Long> groupCommitted,
            long consumedCreateTimeMs,
            Map<TopicPartition, Long> logEndExclusive) {
        if (lagSink == null || tables == null || tables.isEmpty()) {
            return;
        }
        Map<TopicPartition, Long> replayCommitted = replayCommitted(groupCommitted);
        long backlog = DataCheckLag.recordBacklog(
                replayCommitted, requiredEventOffsets, lastConsumedNext, logEndExclusive);
        Long consumedTs = consumedCreateTimeMs > 0L ? consumedCreateTimeMs : null;
        OptionalLong replayTs = replayWatermark.lastAppliedCreateTimeMs();
        Long replayCreateTime = replayTs.isPresent() ? replayTs.getAsLong() : null;
        long now = System.currentTimeMillis();
        for (TableId table : tables) {
            long tableTs = lastTableCreateTimeMs.getOrDefault(table, 0L);
            if (tableTs <= 0L && consumedCreateTimeMs > 0L) {
                tableTs = consumedCreateTimeMs;
            }
            Long tableConsumed = tableTs > 0L ? Long.valueOf(tableTs) : consumedTs;
            long lag = DataCheckLag.lagMs(
                    now,
                    waitingForTarget,
                    tableConsumed,
                    replayCreateTime,
                    backlog);
            lagSink.update(table, lag, waitingForTarget);
        }
        lagSink.flush();
    }

    /**
     * Prefer same-process last-applied + 1 from the migration lag registry; fall back to the
     * replay consumer-group committed offsets (those may lag the in-memory apply by the 3s
     * commit interval).
     */
    private Map<TopicPartition, Long> replayCommitted(Map<TopicPartition, Long> groupCommitted) {
        Map<TopicPartition, Long> fromRegistry = replayWatermark.committedOffsets();
        if (fromRegistry != null && !fromRegistry.isEmpty()) {
            return fromRegistry;
        }
        return groupCommitted == null ? Map.of() : groupCommitted;
    }

    private static TableDefinition read(JdbcConnection connection, TableId tableId) {
        try {
            return connection.readTable(tableId);
        } catch (java.sql.SQLException ex) {
            throw new IllegalStateException("failed to read metadata for " + tableId, ex);
        }
    }

    private CheckerHold checkWithRetry(
            PkBatchChecker checker,
            CheckedTablePair pair,
            List<PkRow> keys,
            InconsistentRowStore store,
            MigrationLogListener logs,
            JdbcConnection source,
            JdbcConnection target,
            JdbcConnectionConfig sourceConfig,
            JdbcConnectionConfig targetConfig,
            List<CheckedTablePair> tables,
            Map<TableId, TableDefinition> sourceDefs,
            Map<TableId, TableDefinition> targetDefs) {
        int attempts = 0;
        while (true) {
            try {
                checker.check(pair, keys, store);
                return new CheckerHold(checker, source, target);
            } catch (RuntimeException ex) {
                if (checkerOverride != null || factory == null || !isRetryable(ex) || attempts >= 2) {
                    throw ex;
                }
                attempts++;
                logs.emit(MigrationLogLevel.WARNING,
                        "retryable JDBC error during incremental check of " + pair.source()
                                + "; reconnecting and retrying the current batch: " + ex.getMessage());
                closeQuietly(source);
                closeQuietly(target);
                source = factory.open(sourceConfig);
                target = factory.open(targetConfig);
                sourceDefs.clear();
                targetDefs.clear();
                loadDefinitions(tables, source, target, sourceDefs, targetDefs);
                checker = new JdbcPkBatchChecker(source, target, sourceDefs::get, targetDefs::get);
            }
        }
    }

    private static void loadDefinitions(
            List<CheckedTablePair> tables,
            JdbcConnection source,
            JdbcConnection target,
            Map<TableId, TableDefinition> sourceDefs,
            Map<TableId, TableDefinition> targetDefs) {
        for (CheckedTablePair pair : tables) {
            TableDefinition sourceDef = read(source, pair.source());
            if (sourceDef == null) {
                throw new IllegalStateException("source table not found: " + pair.source());
            }
            PrimaryKeySupport.validate(pair.source(), sourceDef);
            sourceDefs.put(pair.source(), sourceDef);
            TableDefinition targetDef = read(target, pair.target());
            if (targetDef == null) {
                throw new IllegalStateException("target table not found: " + pair.target());
            }
            PrimaryKeySupport.validate(pair.target(), targetDef);
            targetDefs.put(pair.target(), targetDef);
        }
    }

    static boolean isRetryable(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLTransientException || current instanceof SQLRecoverableException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("communications link failure")
                        || lower.contains("connection reset")
                        || lower.contains("broken pipe")
                        || lower.contains("connection is closed")
                        || lower.contains("no operations allowed after connection closed")
                        || lower.contains("i/o error")
                        || lower.contains("io error")
                        || lower.contains("connection refused")
                        || lower.contains("cannot get a connection")) {
                    return true;
                }
            }
            if (current instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && (state.startsWith("08") || state.equals("40001")
                        || state.equals("40P01") || state.equals("HY000"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void closeQuietly(JdbcConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception ignored) {
            // already closed or pool-managed
        }
    }

    private record CheckerHold(PkBatchChecker checker, JdbcConnection source, JdbcConnection target) {
    }
}
