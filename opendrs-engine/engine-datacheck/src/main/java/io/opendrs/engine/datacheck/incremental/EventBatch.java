package io.opendrs.engine.datacheck.incremental;

import io.opendrs.engine.common.metadata.TableId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;

/**
 * One poll's worth of unique PKs, plus the high-water event offsets (inclusive) of every record
 * in the poll (including skipped ones) so consume position can advance after a successful check.
 */
public final class EventBatch {

    private final Map<TableId, Map<String, PkRow>> rows = new LinkedHashMap<>();
    private final Map<TopicPartition, Long> maxEventOffsets = new LinkedHashMap<>();
    private long maxCreateTimeMs;

    public void noteOffset(TopicPartition partition, long offset) {
        noteRecord(partition, offset, 0L);
    }

    public void noteRecord(TopicPartition partition, long offset, long createTimeMs) {
        maxEventOffsets.merge(partition, offset, Math::max);
        if (createTimeMs > maxCreateTimeMs) {
            maxCreateTimeMs = createTimeMs;
        }
    }

    /** Max Kafka record CreateTime seen in this poll, or 0 if none was recorded. */
    public long maxCreateTimeMs() {
        return maxCreateTimeMs;
    }

    public void put(TableId table, PkRow row) {
        rows.computeIfAbsent(table, key -> new LinkedHashMap<>()).put(row.encoded(), row);
    }

    public boolean isEmpty() {
        return maxEventOffsets.isEmpty();
    }

    public Map<TableId, List<PkRow>> rowsByTable() {
        Map<TableId, List<PkRow>> result = new LinkedHashMap<>();
        for (Map.Entry<TableId, Map<String, PkRow>> entry : rows.entrySet()) {
            result.put(entry.getKey(), List.copyOf(entry.getValue().values()));
        }
        return result;
    }

    public Map<TopicPartition, Long> maxEventOffsets() {
        return Map.copyOf(maxEventOffsets);
    }

    /** Next offset to read after this batch has been processed. */
    public Map<TopicPartition, Long> nextOffsets() {
        Map<TopicPartition, Long> next = new LinkedHashMap<>();
        for (Map.Entry<TopicPartition, Long> entry : maxEventOffsets.entrySet()) {
            next.put(entry.getKey(), entry.getValue() + 1);
        }
        return next;
    }
}
