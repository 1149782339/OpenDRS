package io.opendrs.engine.capture.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendrs.common.progress.MigrationLagListener;
import io.opendrs.engine.capture.BatchApplyHandler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KAFKA replay loop: poll the per-pipeline topic, apply via {@link BatchApplyHandler}, then
 * periodically persist {@link AppliedOffsetStore} and commit the consumer group offset.
 *
 * <p>Commits are <strong>time-based only</strong> (default {@link #COMMIT_INTERVAL} = 3s): after a
 * successful apply the offsets stay in memory until the interval elapses (or {@link #close()}
 * forces a final {@code commitSync}). Order is always apply → saveApplied → commit; the consumer
 * is touched only from this runner's thread. For the async path, {@code pending} is cleared only
 * after a successful {@code commitAsync} callback — a failed commit keeps pending for the next
 * interval (or the shutdown {@code commitSync}).
 */
public final class KafkaReplayRunner implements AutoCloseable, Runnable {

    private static final Logger log = LoggerFactory.getLogger(KafkaReplayRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);

    /** Pure timer: flush applied + consumer offset at most this often (no count cap). */
    static final Duration COMMIT_INTERVAL = Duration.ofSeconds(3);

    private final long pipelineId;
    private final KafkaTransportConfig transport;
    private final BatchApplyFn applyFn;
    private final AutoCloseable applyClose;
    private final AppliedOffsetStore appliedOffsetStore;
    private final SourceRecordEnvelopeCodec codec;
    private final Consumer<String, byte[]> consumer;
    private final LongSupplier nanoTime;
    private final MigrationLagListener lagListener;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread thread;

    private final Map<TopicPartition, OffsetAndMetadata> pendingOffsets = new HashMap<>();
    private PendingApplied pendingApplied;
    private long lastFlushNanos;
    private long lastLogEndNanos;
    private boolean seededCommitted;
    /** Replay holds back until this opens (KAFKA right after a full load); open by default. */
    private volatile io.opendrs.engine.capture.ReplayGate gate = io.opendrs.engine.capture.ReplayGate.OPEN;

    @FunctionalInterface
    interface BatchApplyFn {
        void applyBatch(List<SourceRecord> batch) throws InterruptedException;
    }

    record PendingApplied(String sourceOffsetJson, String topic, int partition, long offset) {
    }

    public KafkaReplayRunner(
            long pipelineId,
            KafkaTransportConfig transport,
            BatchApplyHandler applyHandler,
            AppliedOffsetStore appliedOffsetStore) {
        this(pipelineId, transport, applyHandler, appliedOffsetStore, MigrationLagListener.NONE);
    }

    public KafkaReplayRunner(
            long pipelineId,
            KafkaTransportConfig transport,
            BatchApplyHandler applyHandler,
            AppliedOffsetStore appliedOffsetStore,
            MigrationLagListener lagListener) {
        this(pipelineId, transport, applyHandler::applyBatch, applyHandler, appliedOffsetStore,
                new SourceRecordEnvelopeCodec(), createConsumer(transport), System::nanoTime, lagListener);
    }

    KafkaReplayRunner(
            long pipelineId,
            KafkaTransportConfig transport,
            BatchApplyFn applyFn,
            AutoCloseable applyClose,
            AppliedOffsetStore appliedOffsetStore,
            SourceRecordEnvelopeCodec codec,
            Consumer<String, byte[]> consumer,
            LongSupplier nanoTime) {
        this(pipelineId, transport, applyFn, applyClose, appliedOffsetStore, codec, consumer, nanoTime,
                MigrationLagListener.NONE);
    }

    KafkaReplayRunner(
            long pipelineId,
            KafkaTransportConfig transport,
            BatchApplyFn applyFn,
            AutoCloseable applyClose,
            AppliedOffsetStore appliedOffsetStore,
            SourceRecordEnvelopeCodec codec,
            Consumer<String, byte[]> consumer,
            LongSupplier nanoTime,
            MigrationLagListener lagListener) {
        this.pipelineId = pipelineId;
        this.transport = transport;
        this.applyFn = applyFn;
        this.applyClose = applyClose;
        this.appliedOffsetStore = appliedOffsetStore;
        this.codec = codec;
        this.consumer = consumer;
        this.nanoTime = nanoTime == null ? System::nanoTime : nanoTime;
        this.lagListener = lagListener == null ? MigrationLagListener.NONE : lagListener;
        this.lastFlushNanos = this.nanoTime.getAsLong();
        this.lastLogEndNanos = 0L;
        this.thread = new Thread(this, "opendrs-kafka-replay-" + transport.pipelinePublicId());
        this.thread.setDaemon(true);
    }

    /** Test hook: package constructor with real {@link BatchApplyHandler} wiring preserved. */
    KafkaReplayRunner(
            long pipelineId,
            KafkaTransportConfig transport,
            BatchApplyHandler applyHandler,
            AppliedOffsetStore appliedOffsetStore,
            SourceRecordEnvelopeCodec codec,
            Consumer<String, byte[]> consumer) {
        this(pipelineId, transport, applyHandler::applyBatch, applyHandler, appliedOffsetStore, codec,
                consumer, System::nanoTime);
    }

    /**
     * Makes {@link #run} wait for {@code gate} before it subscribes: capture keeps producing to
     * the topic, nothing is replayed until the gate opens. Must be called before {@link #start}.
     */
    public KafkaReplayRunner gatedBy(io.opendrs.engine.capture.ReplayGate gate) {
        this.gate = gate == null ? io.opendrs.engine.capture.ReplayGate.OPEN : gate;
        return this;
    }

    public void start() {
        thread.start();
        log.info("KAFKA replay started pipelineId={} topic={} group={} commitIntervalMs={}",
                pipelineId, transport.topic(), transport.consumerGroup(), COMMIT_INTERVAL.toMillis());
    }

    /** Waits for the gate; false when the runner was closed first. */
    private boolean awaitGate() throws InterruptedException {
        boolean logged = false;
        while (running.get()) {
            if (gate.awaitOpen(POLL_TIMEOUT.toMillis())) {
                if (logged) {
                    log.info("KAFKA replay gate opened for pipeline {}, replay starts", pipelineId);
                }
                return true;
            }
            if (!logged) {
                log.info("KAFKA replay for pipeline {} waits for the target to be prepared", pipelineId);
                logged = true;
            }
        }
        return false;
    }

    @Override
    public void run() {
        try {
            if (!awaitGate()) {
                return;
            }
            // Subscribed on the runner thread: the consumer is only ever touched from here.
            consumer.subscribe(List.of(transport.topic()));
            while (running.get()) {
                ConsumerRecords<String, byte[]> records = consumer.poll(POLL_TIMEOUT);
                if (!records.isEmpty()) {
                    List<SourceRecord> batch = new ArrayList<>(records.count());
                    ConsumerRecord<String, byte[]> last = null;
                    for (ConsumerRecord<String, byte[]> record : records) {
                        batch.add(codec.deserialize(record.value()));
                        last = record;
                    }
                    applyFn.applyBatch(batch);
                    for (ConsumerRecord<String, byte[]> record : records) {
                        lagListener.emitReplayed(record.partition(), record.offset(), record.timestamp());
                        pendingOffsets.put(
                                new TopicPartition(record.topic(), record.partition()),
                                new OffsetAndMetadata(record.offset() + 1));
                    }
                    if (last != null) {
                        SourceRecord lastSource = batch.get(batch.size() - 1);
                        String sourceOffsetJson = MAPPER.writeValueAsString(
                                lastSource.sourceOffset() == null ? Map.of() : lastSource.sourceOffset());
                        pendingApplied = new PendingApplied(
                                sourceOffsetJson, last.topic(), last.partition(), last.offset());
                    }
                }
                publishLogEndIfDue();
                // Interval-only flush (also runs on empty polls so the timer advances while idle).
                flushIfDue(false);
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.info("KAFKA replay interrupted for pipeline {}", pipelineId);
        }
        catch (Exception ex) {
            running.set(false);
            log.error("KAFKA replay failed for pipeline {}", pipelineId, ex);
            throw new IllegalStateException("KAFKA replay failed for pipeline " + pipelineId, ex);
        }
        finally {
            try {
                flushIfDue(true);
            }
            catch (Exception ex) {
                log.warn("KAFKA replay final flush failed for pipeline {}: {}", pipelineId, ex.toString());
            }
            try {
                consumer.close(Duration.ofSeconds(5));
            }
            catch (Exception ex) {
                log.debug("consumer close: {}", ex.getMessage());
            }
        }
    }

    /** Test-only: stage a successful apply as pending without going through {@link #run}. */
    void notePendingForTest(String topic, int partition, long offset, String sourceOffsetJson) {
        pendingOffsets.put(new TopicPartition(topic, partition), new OffsetAndMetadata(offset + 1));
        pendingApplied = new PendingApplied(sourceOffsetJson, topic, partition, offset);
    }

    private static final long LOG_END_INTERVAL_NANOS = 1_000_000_000L;

    /**
     * Refresh topic log-end (exclusive) about once a second. No timestamp seek: offsets
     * only. Combined with locally tracked produce/apply CreateTime in the lag registry.
     */
    private void publishLogEndIfDue() {
        long now = nanoTime.getAsLong();
        if (lastLogEndNanos > 0 && now - lastLogEndNanos < LOG_END_INTERVAL_NANOS) {
            return;
        }
        var assignment = consumer.assignment();
        if (assignment == null || assignment.isEmpty()) {
            return;
        }
        seedAppliedFromCommitted(assignment);
        lastLogEndNanos = now;
        long atMs = System.currentTimeMillis();
        Map<TopicPartition, Long> end;
        try {
            end = consumer.endOffsets(assignment);
        } catch (RuntimeException ex) {
            log.debug("endOffsets probe failed (non-blocking): {}", ex.toString());
            return;
        }
        if (end == null) {
            return;
        }
        for (Map.Entry<TopicPartition, Long> entry : end.entrySet()) {
            if (entry.getValue() != null) {
                lagListener.emitLogEnd(entry.getKey().partition(), entry.getValue(), atMs);
            }
        }
    }

    /**
     * After restart the in-memory applied offset is empty while topic log-end is not,
     * which would report a huge backlog until the first apply. Seed from the consumer
     * group's already-committed next-to-read offset (no timestamp).
     */
    private void seedAppliedFromCommitted(java.util.Set<TopicPartition> assignment) {
        if (seededCommitted) {
            return;
        }
        Map<TopicPartition, OffsetAndMetadata> committed;
        try {
            committed = consumer.committed(assignment);
        } catch (RuntimeException ex) {
            log.debug("committed-offset probe failed (non-blocking): {}", ex.toString());
            return;
        }
        if (committed == null) {
            return;
        }
        for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committed.entrySet()) {
            OffsetAndMetadata meta = entry.getValue();
            if (meta != null && meta.offset() > 0L) {
                lagListener.emitReplayed(entry.getKey().partition(), meta.offset() - 1L, 0L);
            }
        }
        seededCommitted = true;
    }

    /**
     * Persist pending applied row then commit consumer offsets. {@code force=true} uses
     * {@code commitSync} (shutdown); otherwise {@code commitAsync} after {@link #COMMIT_INTERVAL}.
     *
     * <p>Async path clears {@code pending*} only in the <em>success</em> callback so a failed
     * commit can be retried on the next tick or by the final sync flush. If newer applies land
     * while a commit is in flight, only the offsets that match the committed map are dropped.
     */
    void flushIfDue(boolean force) {
        if (pendingApplied == null || pendingOffsets.isEmpty()) {
            return;
        }
        long now = nanoTime.getAsLong();
        if (!force && Duration.ofNanos(now - lastFlushNanos).compareTo(COMMIT_INTERVAL) < 0) {
            return;
        }
        PendingApplied toFlush = pendingApplied;
        Map<TopicPartition, OffsetAndMetadata> toCommit = Map.copyOf(pendingOffsets);
        appliedOffsetStore.saveApplied(
                pipelineId,
                toFlush.sourceOffsetJson(),
                toFlush.topic(),
                toFlush.partition(),
                toFlush.offset());
        lastFlushNanos = now;
        if (force) {
            consumer.commitSync(toCommit);
            clearPendingAfterCommitSuccess(toFlush, toCommit);
            return;
        }
        consumer.commitAsync(toCommit, (offsets, exception) -> {
            if (exception != null) {
                log.warn("KAFKA replay commitAsync failed pipelineId={}: {}",
                        pipelineId, exception.toString());
                // Keep pending so the next interval (or close/force) can retry.
                return;
            }
            clearPendingAfterCommitSuccess(toFlush, toCommit);
        });
    }

    /**
     * Drop pending state that this commit covered. If {@link #pendingApplied} moved forward while
     * the async commit was in flight, only remove partition offsets that still match.
     */
    private void clearPendingAfterCommitSuccess(
            PendingApplied flushed, Map<TopicPartition, OffsetAndMetadata> committed) {
        if (pendingApplied == flushed) {
            pendingOffsets.clear();
            pendingApplied = null;
            return;
        }
        for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committed.entrySet()) {
            OffsetAndMetadata current = pendingOffsets.get(entry.getKey());
            if (current != null && current.offset() == entry.getValue().offset()) {
                pendingOffsets.remove(entry.getKey());
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            consumer.wakeup();
        }
        catch (Exception ignored) {
            // ignore
        }
        try {
            thread.join(Duration.ofSeconds(15).toMillis());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        try {
            if (applyClose != null) {
                applyClose.close();
            }
        }
        catch (Exception ex) {
            log.debug("apply close: {}", ex.getMessage());
        }
    }

    private static Consumer<String, byte[]> createConsumer(KafkaTransportConfig transport) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, transport.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, transport.consumerGroup());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "opendrs-replay-" + transport.pipelinePublicId());
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "500");
        return new KafkaConsumer<>(props);
    }
}
