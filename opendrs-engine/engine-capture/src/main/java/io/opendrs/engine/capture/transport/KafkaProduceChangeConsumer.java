package io.opendrs.engine.capture.transport;

import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.RecordChangeEvent;
import io.opendrs.engine.replay.GtidLoopConfig;
import io.opendrs.engine.replay.ReplayConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
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
 * KAFKA capture consumer: filter GTID loop-backs, batch-produce SourceRecord envelopes to the
 * pipeline topic, and only then {@code markProcessed}. {@code debezium_offsets} therefore means
 * "successfully produced", not "applied to the target".
 */
public final class KafkaProduceChangeConsumer
        implements DebeziumEngine.ChangeConsumer<RecordChangeEvent<SourceRecord>>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaProduceChangeConsumer.class);

    private final long taskId;
    private final ReplayConfig config;
    private final KafkaTransportConfig transport;
    private final SourceRecordEnvelopeCodec codec;
    private final Producer<String, byte[]> producer;

    public KafkaProduceChangeConsumer(long taskId, ReplayConfig config, KafkaTransportConfig transport) {
        this(taskId, config, transport, new SourceRecordEnvelopeCodec(), createProducer(transport));
    }

    KafkaProduceChangeConsumer(
            long taskId,
            ReplayConfig config,
            KafkaTransportConfig transport,
            SourceRecordEnvelopeCodec codec,
            Producer<String, byte[]> producer) {
        this.taskId = taskId;
        this.config = config;
        this.transport = transport;
        this.codec = codec;
        this.producer = producer;
        log.info("KAFKA produce consumer ready for task {} topic={}", taskId, transport.topic());
    }

    @Override
    public void handleBatch(
            List<RecordChangeEvent<SourceRecord>> records,
            DebeziumEngine.RecordCommitter<RecordChangeEvent<SourceRecord>> committer)
            throws InterruptedException {
        List<SourceRecord> batch = filterLoopBacks(records);
        if (!batch.isEmpty()) {
            config.getMetrics().captured(config.getDirection(), taskId, batch.size());
            produceAll(batch);
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

    private List<SourceRecord> filterLoopBacks(List<RecordChangeEvent<SourceRecord>> records) {
        List<SourceRecord> batch = new ArrayList<>(records.size());
        GtidLoopConfig loop = config.getGtidLoop();
        for (RecordChangeEvent<SourceRecord> event : records) {
            SourceRecord record = event.record();
            if (loop != null && loop.isLoopBack(sourceGtid(record))) {
                continue;
            }
            batch.add(record);
        }
        return batch;
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
        producer.close(Duration.ofSeconds(10));
    }
}
