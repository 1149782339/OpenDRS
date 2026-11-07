package io.opendrs.engine.capture.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;

class SourceRecordEnvelopeCodecTest {

    private final SourceRecordEnvelopeCodec codec = new SourceRecordEnvelopeCodec();

    @Test
    void roundTripsEnvelopePreservingTopicOffsetAndPayload() {
        Schema valueSchema = SchemaBuilder.struct()
                .field("id", Schema.INT32_SCHEMA)
                .field("name", Schema.STRING_SCHEMA)
                .build();
        Struct value = new Struct(valueSchema).put("id", 7).put("name", "alice");
        SourceRecord original = new SourceRecord(
                Map.of("server", "opendrs"),
                Map.of("file", "mysql-bin.000001", "pos", 1234L),
                "opendrs.pipeline.1.hr.emp",
                null,
                Schema.STRING_SCHEMA,
                "pk-1",
                valueSchema,
                value,
                1_700_000_000_000L);

        SourceRecord restored = codec.deserialize(codec.serialize(original));

        assertThat(restored.topic()).isEqualTo(original.topic());
        assertThat(restored.sourcePartition()).isEqualTo(original.sourcePartition());
        assertThat(restored.sourceOffset().get("file")).isEqualTo("mysql-bin.000001");
        assertThat(((Number) restored.sourceOffset().get("pos")).longValue()).isEqualTo(1234L);
        assertThat(restored.key()).isEqualTo("pk-1");
        Struct restoredValue = (Struct) restored.value();
        assertThat(restoredValue.getInt32("id")).isEqualTo(7);
        assertThat(restoredValue.getString("name")).isEqualTo("alice");
    }
}
