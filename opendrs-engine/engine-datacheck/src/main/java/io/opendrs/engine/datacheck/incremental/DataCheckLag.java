package io.opendrs.engine.datacheck.incremental;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.common.TopicPartition;

/**
 * Datacheck lag versus the migration replay watermark: time from locally tracked record
 * CreateTimes, plus a record-count backlog. Zero when datacheck is caught up with replay
 * and is not waiting for the target.
 */
public final class DataCheckLag {

    private DataCheckLag() {
    }

    /**
     * Record distance between datacheck's required/consumed offsets and replay's committed
     * next-to-read (or, when idle, topic log-end).
     *
     * @param replayCommittedNext replay next-to-read offsets (applied + 1, or group committed)
     * @param requiredEventOffsets inclusive event offsets the current wait needs, or null
     * @param consumedNext datacheck next-to-read, or null
     * @param logEndExclusive topic end, or null
     */
    public static long recordBacklog(
            Map<TopicPartition, Long> replayCommittedNext,
            Map<TopicPartition, Long> requiredEventOffsets,
            Map<TopicPartition, Long> consumedNext,
            Map<TopicPartition, Long> logEndExclusive) {
        long backlog = 0L;
        if (requiredEventOffsets != null && !requiredEventOffsets.isEmpty()) {
            for (Map.Entry<TopicPartition, Long> entry : requiredEventOffsets.entrySet()) {
                long needNext = entry.getValue() + 1L;
                long committed = get(replayCommittedNext, entry.getKey(), 0L);
                backlog = Math.max(backlog, Math.max(0L, needNext - committed));
            }
            return backlog;
        }
        Set<TopicPartition> partitions = new HashSet<>();
        if (consumedNext != null) {
            partitions.addAll(consumedNext.keySet());
        }
        if (replayCommittedNext != null) {
            partitions.addAll(replayCommittedNext.keySet());
        }
        if (logEndExclusive != null) {
            partitions.addAll(logEndExclusive.keySet());
        }
        for (TopicPartition partition : partitions) {
            long committed = get(replayCommittedNext, partition, 0L);
            if (logEndExclusive != null && logEndExclusive.containsKey(partition)) {
                backlog = Math.max(backlog, Math.max(0L, logEndExclusive.get(partition) - committed));
            }
            if (consumedNext != null && consumedNext.containsKey(partition)) {
                backlog = Math.max(backlog, Math.abs(consumedNext.get(partition) - committed));
            }
        }
        return backlog;
    }

    /**
     * Time lag in milliseconds. Uses CreateTime of the last datacheck-consumed record versus
     * the last replayed record when both are known. While waiting and the replay timestamp is
     * missing, falls back to the age of the pending consumed record. Never treats a record
     * count as milliseconds.
     */
    public static long lagMs(
            long nowMs,
            boolean waitingForTarget,
            Long lastConsumedCreateTimeMs,
            Long lastReplayCreateTimeMs,
            long recordBacklog) {
        if (!waitingForTarget && recordBacklog <= 0L) {
            return 0L;
        }
        if (lastConsumedCreateTimeMs != null && lastReplayCreateTimeMs != null) {
            return Math.max(0L, Math.abs(lastConsumedCreateTimeMs - lastReplayCreateTimeMs));
        }
        if (waitingForTarget && lastConsumedCreateTimeMs != null && lastConsumedCreateTimeMs > 0L) {
            return Math.max(0L, nowMs - lastConsumedCreateTimeMs);
        }
        return 0L;
    }

    private static long get(Map<TopicPartition, Long> map, TopicPartition key, long defaultValue) {
        if (map == null) {
            return defaultValue;
        }
        Long value = map.get(key);
        return value == null ? defaultValue : value;
    }
}
