package io.opendrs.engine.datacheck.incremental;

import java.time.Duration;
import java.util.Objects;

public record IncrementalDataCheckConfig(
        String topic,
        String replayGroup,
        Duration waitTimeout,
        Duration waitPollInterval) {

    public IncrementalDataCheckConfig {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(replayGroup, "replayGroup");
        if (waitTimeout == null) {
            waitTimeout = Duration.ofMinutes(5);
        }
        if (waitPollInterval == null) {
            waitPollInterval = Duration.ofSeconds(1);
        }
    }
}
