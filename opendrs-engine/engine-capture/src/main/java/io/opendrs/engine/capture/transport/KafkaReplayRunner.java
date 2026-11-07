package io.opendrs.engine.capture.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opendrs.engine.capture.BatchApplyHandler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KAFKA replay loop: poll the per-pipeline topic, apply via {@link BatchApplyHandler}, then
 * persist {@link AppliedOffsetStore}. Kafka consumer-group offset tracks read progress; applied
 * offsets track target progress (required under at-least-once).
 */
public final class KafkaReplayRunner implements AutoCloseable, Runnable {

    private static final Logger log = LoggerFactory.getLogger(KafkaReplayRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);

    private final long pipelineId;
    private final KafkaTransportConfig transport;
    private final BatchApplyHandler applyHandler;
    private final AppliedOffsetStore appliedOffsetStore;
    private final SourceRecordEnvelopeCodec codec;
    private final Consumer<String, byte[]> consumer;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread thread;

    public KafkaReplayRunner(
            long pipelineId,
            KafkaTransportConfig transport,
            BatchApplyHandler applyHandler,
            AppliedOffsetStore appliedOffsetStore) {
        this(pipelineId, transport, applyHandler, appliedOffsetStore, new SourceRecordEnvelopeCodec(),
                createConsumer(transport));
    }

    KafkaReplayRunner(
            long pipelineId,
            KafkaTransportConfig transport,
            BatchApplyHandler applyHandler,
            AppliedOffsetStore appliedOffsetStore,
            SourceRecordEnvelopeCodec codec,
            Consumer<String, byte[]> consumer) {
        this.pipelineId = pipelineId;
        this.transport = transport;
        this.applyHandler = applyHandler;
        this.appliedOffsetStore = appliedOffsetStore;
        this.codec = codec;
        this.consumer = consumer;
        this.thread = new Thread(this, "opendrs-kafka-replay-" + transport.pipelinePublicId());
        this.thread.setDaemon(true);
    }

    public void start() {
        consumer.subscribe(List.of(transport.topic()));
        thread.start();
        log.info("KAFKA replay started pipelineId={} topic={} group={}",
                pipelineId, transport.topic(), transport.consumerGroup());
    }

    @Override
    public void run() {
        try {
            while (running.get()) {
                ConsumerRecords<String, byte[]> records = consumer.poll(POLL_TIMEOUT);
                if (records.isEmpty()) {
                    continue;
                }
                List<SourceRecord> batch = new ArrayList<>(records.count());
                ConsumerRecord<String, byte[]> last = null;
                for (ConsumerRecord<String, byte[]> record : records) {
                    batch.add(codec.deserialize(record.value()));
                    last = record;
                }
                applyHandler.applyBatch(batch);
                if (last != null) {
                    SourceRecord lastSource = batch.get(batch.size() - 1);
                    String sourceOffsetJson = MAPPER.writeValueAsString(
                            lastSource.sourceOffset() == null ? Map.of() : lastSource.sourceOffset());
                    appliedOffsetStore.saveApplied(
                            pipelineId, sourceOffsetJson, last.topic(), last.partition(), last.offset());
                }
                // Commit after applied row is durable so a crash between apply and commit still
                // redelivers (at-least-once). Applied store is the truth for "target caught up".
                consumer.commitSync();
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
                consumer.close(Duration.ofSeconds(5));
            }
            catch (Exception ex) {
                log.debug("consumer close: {}", ex.getMessage());
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
        applyHandler.close();
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
