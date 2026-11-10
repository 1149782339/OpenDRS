package io.opendrs.engine.datacheck.incremental;

import io.opendrs.engine.capture.transport.SourceRecordEnvelopeCodec;
import io.opendrs.engine.common.log.MigrationLogLevel;
import io.opendrs.engine.common.log.MigrationLogListener;
import io.opendrs.engine.common.metadata.TableDefinition;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.common.naming.ColumnNamingStrategy;
import io.opendrs.engine.common.naming.TableNamingStrategy;
import io.opendrs.engine.datacheck.InconsistentRowStore;
import io.opendrs.engine.jdbc.JdbcConnection;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import io.opendrs.engine.jdbc.JdbcConnectionFactory;
import io.opendrs.engine.replay.applier.SourceRecordConverter;
import io.opendrs.engine.replay.event.ChangeEvent;
import io.opendrs.engine.replay.event.DataChangeEvent;
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
        try (JdbcConnection source = factory == null ? null : factory.open(sourceConfig);
                JdbcConnection target = factory == null ? null : factory.open(targetConfig)) {
            PkBatchChecker checker = checkerOverride != null
                    ? checkerOverride
                    : new JdbcPkBatchChecker(
                            source, target, id -> read(source, id), id -> read(target, id));
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
                    pending = decode(records, bySource.keySet(), logs);
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
                    checker.check(pair, entry.getValue(), store);
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
        }
    }

    private EventBatch decode(
            List<ConsumerRecord<String, byte[]>> records,
            Set<TableId> checked,
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
            List<Object> bind = PrimaryKeyEncoder.bindValues(change);
            String encoded = PrimaryKeyEncoder.encode(bind);
            batch.put(lookup(checked, table), new PkRow(encoded, bind));
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
        return tables.contains(table) || lookup(tables, table) != null;
    }

    private static TableId lookup(Set<TableId> tables, TableId table) {
        if (tables.contains(table)) {
            return table;
        }
        for (TableId candidate : tables) {
            if (candidate.equals(table)) {
                return candidate;
            }
        }
        return null;
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
        long lag = DataCheckLag.lagMs(
                System.currentTimeMillis(),
                waitingForTarget,
                consumedTs,
                replayCreateTime,
                backlog);
        for (TableId table : tables) {
            lagSink.update(table, lag);
        }
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

}
