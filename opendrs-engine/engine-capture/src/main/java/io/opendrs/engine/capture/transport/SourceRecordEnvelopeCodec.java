package io.opendrs.engine.capture.transport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.source.SourceRecord;

/**
 * Serializes / deserializes a Debezium embedded {@link SourceRecord} as a JSON envelope that
 * keeps Connect schemas + payloads (key/value), topic, and source partition/offset.
 *
 * <p><strong>Not</strong> the demo flat Avro record: that shape drops the Debezium envelope and
 * cannot drive {@code engine-replay}. Apicurio Registry URL is orthogonal — this codec uses
 * Kafka Connect {@link JsonConverter} so schemas travel inline with each message for now.
 */
public final class SourceRecordEnvelopeCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JsonConverter keyConverter;
    private final JsonConverter valueConverter;

    public SourceRecordEnvelopeCodec() {
        this.keyConverter = newJsonConverter(true);
        this.valueConverter = newJsonConverter(false);
    }

    public byte[] serialize(SourceRecord record) {
        try {
            Map<String, Object> envelope = new HashMap<>();
            envelope.put("topic", record.topic());
            envelope.put("kafkaPartition", record.kafkaPartition());
            envelope.put("sourcePartition", record.sourcePartition());
            envelope.put("sourceOffset", record.sourceOffset());
            envelope.put("timestamp", record.timestamp());
            if (record.keySchema() != null || record.key() != null) {
                envelope.put("key", parseJson(keyConverter.fromConnectData(
                        record.topic(), record.keySchema(), record.key())));
            }
            if (record.valueSchema() != null || record.value() != null) {
                envelope.put("value", parseJson(valueConverter.fromConnectData(
                        record.topic(), record.valueSchema(), record.value())));
            }
            return MAPPER.writeValueAsBytes(envelope);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize SourceRecord envelope", ex);
        }
    }

    @SuppressWarnings("unchecked")
    public SourceRecord deserialize(byte[] payload) {
        try {
            Map<String, Object> envelope = MAPPER.readValue(payload, MAP_TYPE);
            String topic = (String) envelope.get("topic");
            Integer kafkaPartition = envelope.get("kafkaPartition") == null
                    ? null
                    : ((Number) envelope.get("kafkaPartition")).intValue();
            Map<String, ?> sourcePartition = (Map<String, ?>) envelope.get("sourcePartition");
            Map<String, ?> sourceOffset = (Map<String, ?>) envelope.get("sourceOffset");
            Long timestamp = envelope.get("timestamp") == null
                    ? null
                    : ((Number) envelope.get("timestamp")).longValue();

            SchemaAndValue key = SchemaAndValue.NULL;
            if (envelope.get("key") != null) {
                byte[] keyBytes = MAPPER.writeValueAsBytes(envelope.get("key"));
                key = keyConverter.toConnectData(topic, keyBytes);
            }
            SchemaAndValue value = SchemaAndValue.NULL;
            if (envelope.get("value") != null) {
                byte[] valueBytes = MAPPER.writeValueAsBytes(envelope.get("value"));
                value = valueConverter.toConnectData(topic, valueBytes);
            }
            return new SourceRecord(
                    sourcePartition,
                    sourceOffset,
                    topic,
                    kafkaPartition,
                    key.schema(),
                    key.value(),
                    value.schema(),
                    value.value(),
                    timestamp);
        }
        catch (IOException ex) {
            throw new IllegalStateException("Failed to deserialize SourceRecord envelope", ex);
        }
    }

    private static Object parseJson(byte[] json) throws IOException {
        if (json == null) {
            return null;
        }
        return MAPPER.readValue(json, Object.class);
    }

    private static JsonConverter newJsonConverter(boolean isKey) {
        JsonConverter converter = new JsonConverter();
        Map<String, Object> configs = new HashMap<>();
        configs.put("schemas.enable", true);
        converter.configure(configs, isKey);
        return converter;
    }

    /** Human-readable form for tests / diagnostics. */
    public String serializeToString(SourceRecord record) {
        return new String(serialize(record), StandardCharsets.UTF_8);
    }
}
