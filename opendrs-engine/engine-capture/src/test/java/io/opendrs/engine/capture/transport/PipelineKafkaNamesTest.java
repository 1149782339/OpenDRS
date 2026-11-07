package io.opendrs.engine.capture.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PipelineKafkaNamesTest {

    @Test
    void derivesTopicAndGroupFromPublicId() {
        String id = "11111111-2222-3333-4444-555555555555";
        assertThat(PipelineKafkaNames.topic(id)).isEqualTo("opendrs.pipeline." + id);
        assertThat(PipelineKafkaNames.consumerGroup(id)).isEqualTo("opendrs.replay." + id);
    }

    @Test
    void rejectsBlankPublicId() {
        assertThatThrownBy(() -> PipelineKafkaNames.topic(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PipelineKafkaNames.consumerGroup(null))
                .isInstanceOf(NullPointerException.class);
    }
}
