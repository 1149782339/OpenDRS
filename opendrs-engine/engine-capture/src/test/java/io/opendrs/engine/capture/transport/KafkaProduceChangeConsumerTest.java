package io.opendrs.engine.capture.transport;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

class KafkaProduceChangeConsumerTest {

    private static final Schema SOURCE_SCHEMA = SchemaBuilder.struct()
            .field("snapshot", Schema.OPTIONAL_STRING_SCHEMA)
            .field("gtid", Schema.OPTIONAL_STRING_SCHEMA)
            .build();

    private static final Schema ENVELOPE = SchemaBuilder.struct()
            .name("test.Envelope")
            .field("op", Schema.STRING_SCHEMA)
            .field("source", SOURCE_SCHEMA)
            .build();

    @Test
    void snapshotMarkerRoutesAsSnapshot() {
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(record("r", "true"))).isTrue();
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(record("r", "first"))).isTrue();
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(record("r", "last"))).isTrue();
    }

    @Test
    void readOpWithoutMarkerStillSnapshot() {
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(record("r", null))).isTrue();
    }

    @Test
    void streamingOpsBypassSnapshot() {
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(record("c", null))).isFalse();
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(record("u", null))).isFalse();
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(record("d", null))).isFalse();
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(record("c", "false"))).isFalse();
    }

    @Test
    void nullRecordIsNotSnapshot() {
        assertThat(KafkaProduceChangeConsumer.isSnapshotRecord(null)).isFalse();
    }

    private static SourceRecord record(String op, String snapshotMarker) {
        Struct source = new Struct(SOURCE_SCHEMA);
        if (snapshotMarker != null) {
            source.put("snapshot", snapshotMarker);
        }
        Struct value = new Struct(ENVELOPE).put("op", op).put("source", source);
        return new SourceRecord(
                null, null, "server.db.t", null,
                Schema.STRING_SCHEMA, "k",
                ENVELOPE, value);
    }
}
