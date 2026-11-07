package io.opendrs.engine.capture.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TransportSpecTest {

    @Test
    void directIsDefaultSafePath() {
        assertThat(TransportSpec.direct().channel()).isEqualTo(TransportChannel.DIRECT);
        assertThat(TransportSpec.direct().isKafka()).isFalse();
    }

    @Test
    void kafkaRequiresConfig() {
        assertThatThrownBy(() -> new TransportSpec(TransportChannel.KAFKA, null))
                .isInstanceOf(IllegalArgumentException.class);
        KafkaTransportConfig cfg = new KafkaTransportConfig(
                "127.0.0.1:9092", "http://127.0.0.1:8081", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        TransportSpec spec = TransportSpec.kafka(cfg);
        assertThat(spec.isKafka()).isTrue();
        assertThat(spec.kafka().topic()).isEqualTo(
                "opendrs.pipeline.aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertThat(spec.kafka().consumerGroup()).isEqualTo(
                "opendrs.replay.aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    }
}
