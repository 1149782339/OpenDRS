package io.opendrs.engine.capture.transport;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.opendrs.engine.capture.BatchApplyHandler;
import io.opendrs.engine.replay.GtidLoopConfig;
import io.opendrs.engine.replay.ReplayConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KAFKA capture consumer with snapshot bypass: full-load ({@code source.snapshot} / op {@code r})
 * rows are applied DIRECT (apply → mark, same as {@code ReplayApplyChangeConsumer}); only
 * incremental/streaming records are batch-produced to the pipeline topic. For produced records,
 * {@code debezium_offsets} means "successfully produced", not "applied to the target".
 */
public final class KafkaProduceChangeConsumer
        implements DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaProduceChangeConsumer.class);

    /** Lower-cased {@code io.debezium.connector.SnapshotRecord} names that mean "snapshot phase". */
    private static final Set<String> SNAPSHOT_MARKERS =
            Set.of("true", "first", "first_in_data_collection", "last_in_data_collection", "last");

    private final long taskId;
    private final ReplayConfig config;
    private final KafkaTransportConfig transport;
    private final SourceRecordEnvelopeCodec codec;
    private final Producer<String, byte[]> producer;
    /** DIRECT apply path for snapshot / full-load records; never null in production. */
    private final BatchApplyHandler snapshotApplyHandler;

    public KafkaProduceChangeConsumer(
            long taskId,
            ReplayConfig config,
            KafkaTransportConfig transport,
            BatchApplyHandler snapshotApplyHandler) {
        this(taskId, config, transport, snapshotApplyHandler, new SourceRecordEnvelopeCodec(), createProducer(transport));
    }

    KafkaProduceChangeConsumer(
            long taskId,
            ReplayConfig config,
            KafkaTransportConfig transport,
            BatchApplyHandler snapshotApplyHandler,
            SourceRecordEnvelopeCodec codec,
            Producer<String, byte[]> producer) {
        if (snapshotApplyHandler == null) {
            throw new IllegalArgumentException("snapshotApplyHandler is required (FULL bypasses Kafka)");
        }
        this.taskId = taskId;
        this.config = config;
        this.transport = transport;
        this.snapshotApplyHandler = snapshotApplyHandler;
        this.codec = codec;
        this.producer = producer;
        log.info(
                "KAFKA produce consumer ready for task {} topic={} (snapshot→DIRECT, stream→Kafka)",
                taskId,
                transport.topic());
    }

    @Override
    public void handleBatch(
            List<RecordChangeEvent<SourceRecord>> records,
            DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer)
            throws InterruptedException {
        List<SourceRecord> snapshot = new ArrayList<>(records.size());
        List<SourceRecord> streaming = new ArrayList<>(records.size());
        splitNonLoopBack(records, snapshot, streaming);

        // FULL: apply → mark (DIRECT semantics). INCREMENTAL: produce → mark.
        if (!snapshot.isEmpty()) {
            snapshotApplyHandler.applyBatch(snapshot);
        }
        if (!streaming.isEmpty()) {
            config.getMetrics().captured(config.getDirection(), taskId, streaming.size());
            produceAll(streaming);
        }
        for (RecordChangeEvent<SourceRecord> event : records) {
            committer.markProcessed(event);
        }
        committer.markBatchFinished();
    }

    private void produceAll(List<SourceRecord> batch) {
        String topic = transport.topic();
        List<Future<RecordMetadata>> futures = new ArrayList<>(batch.size());
        for (SourceRecord record : batch) {
            byte[] payload = codec.serialize(record);
            // Key by Debezium topic (server.db.table) to keep per-table ordering within a partition.
            String key = record.topic();
            futures.add(producer.send(new ProducerRecord<>(topic, key, payload)));
        }
        producer.flush();
        for (Future<RecordMetadata> future : futures) {
            try {
                future.get();
            }
            catch (Exception ex) {
                throw new IllegalStateException(
                        "Failed to produce CDC batch to Kafka topic " + topic + " for task " + taskId, ex);
            }
        }
    }

    private void splitNonLoopBack(
            List<RecordChangeEvent<SourceRecord>> records,
            List<SourceRecord> snapshot,
            List<SourceRecord> streaming) {
        GtidLoopConfig loop = config.getGtidLoop();
        for (RecordChangeEvent<SourceRecord> event : records) {
            SourceRecord record = event.record();
            if (loop != null && loop.isLoopBack(sourceGtid(record))) {
                continue;
            }
            if (isSnapshotRecord(record)) {
                snapshot.add(record);
            }
            else {
                streaming.add(record);
            }
        }
    }

    /**
     * True for Debezium snapshot-phase records that must bypass Kafka and apply DIRECT.
     * Uses {@code source.snapshot} markers when present; falls back to envelope op {@code r}
     * (READ) for snapshot row events.
     */
    static boolean isSnapshotRecord(SourceRecord record) {
        if (record == null) {
            return false;
        }
        Object value = record.value();
        if (!(value instanceof Struct struct)) {
            return false;
        }
        if (hasSnapshotMarker(struct)) {
            return true;
        }
        // Envelope op "r" = snapshot READ row (some records may omit source.snapshot).
        if (struct.schema().field("op") != null) {
            Object op = struct.get("op");
            if (op != null && "r".equalsIgnoreCase(op.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSnapshotMarker(Struct value) {
        if (value.schema().field("source") == null) {
            return false;
        }
        Struct source = value.getStruct("source");
        if (source == null || source.schema().field("snapshot") == null) {
            return false;
        }
        Object snapshot = source.get("snapshot");
        return snapshot != null && SNAPSHOT_MARKERS.contains(snapshot.toString().toLowerCase(Locale.ROOT));
    }

    private static String sourceGtid(SourceRecord record) {
        Object value = record.value();
        if (!(value instanceof Struct struct) || struct.schema().field("source") == null) {
            return null;
        }
        Struct source = struct.getStruct("source");
        if (source == null || source.schema().field("gtid") == null) {
            return null;
        }
        return source.getString("gtid");
    }

    private static Producer<String, byte[]> createProducer(KafkaTransportConfig transport) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, transport.bootstrapServers());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "20");
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(64 * 1024));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "opendrs-produce-" + transport.pipelinePublicId());
        return new KafkaProducer<>(props);
    }

    @Override
    public void close() {
        try {
            snapshotApplyHandler.close();
        }
        finally {
            producer.close(Duration.ofSeconds(10));
        }
    }
}
