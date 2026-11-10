package io.opendrs.engine.datacheck.incremental;

import java.util.Map;
import org.apache.kafka.common.TopicPartition;

/** Persists the check's own consume position (next offset to read) for stop/resume. */
public interface OffsetStore {

    void saveConsumed(Map<TopicPartition, Long> nextOffsets);
}
