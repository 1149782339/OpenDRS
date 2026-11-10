package io.opendrs.engine.datacheck.incremental;

import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.apache.kafka.common.TopicPartition;

/**
 * Waits until the replay consumer group's committed offset (next-to-read) has passed each
 * required event offset, i.e. {@code committed >= requiredOffset + 1}.
 */
public final class ReplayOffsetGate {

    public record Result(boolean ready, long lagMs, String detail) {
        public static Result ok() {
            return new Result(true, 0L, null);
        }

        public static Result timeout(long lagMs, String detail) {
            return new Result(false, lagMs, detail);
        }
    }

    private final ConsumerGroupOffsets offsets;
    private final String replayGroup;
    private final Duration timeout;
    private final Duration pollInterval;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /**
     * Invoked on every wait poll so callers can publish live lag while blocked on the target.
     *
     * @param committed replay group committed next-to-read offsets at this poll
     * @param elapsedMs time spent in this wait so far (not the lag value)
     * @param ready true when committed has passed every required event offset
     */
    @FunctionalInterface
    public interface WaitListener {
        void onPoll(Map<TopicPartition, Long> committed, long elapsedMs, boolean ready);
    }

    public ReplayOffsetGate(
            ConsumerGroupOffsets offsets,
            String replayGroup,
            Duration timeout,
            Duration pollInterval) {
        this(offsets, replayGroup, timeout, pollInterval, System::nanoTime, ReplayOffsetGate::sleep);
    }

    public ReplayOffsetGate(
            ConsumerGroupOffsets offsets,
            String replayGroup,
            Duration timeout,
            Duration pollInterval,
            LongSupplier nanoTime,
            Sleeper sleeper) {
        this.offsets = offsets;
        this.replayGroup = replayGroup;
        this.timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
        this.pollInterval = pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()
                ? Duration.ofSeconds(1)
                : pollInterval;
        this.nanoTime = nanoTime == null ? System::nanoTime : nanoTime;
        this.sleeper = sleeper == null ? ReplayOffsetGate::sleep : sleeper;
    }

    private static void sleep(Duration duration) throws InterruptedException {
        Thread.sleep(Math.max(0L, duration.toMillis()));
    }

    /**
     * @param requiredEventOffsets maximum event offset (inclusive) that must already be applied,
     *     per partition
     */
    public Result await(
            Map<TopicPartition, Long> requiredEventOffsets, BooleanSupplier stopRequested) {
        return await(requiredEventOffsets, stopRequested, null);
    }

    public Result await(
            Map<TopicPartition, Long> requiredEventOffsets,
            BooleanSupplier stopRequested,
            WaitListener waitListener) {
        if (requiredEventOffsets == null || requiredEventOffsets.isEmpty()) {
            return Result.ok();
        }
        long deadline = nanoTime.getAsLong() + timeout.toNanos();
        Collection<TopicPartition> partitions = requiredEventOffsets.keySet();
        while (true) {
            if (stopRequested != null && stopRequested.getAsBoolean()) {
                return Result.timeout(elapsedMs(deadline), "stopped while waiting for replay");
            }
            Map<TopicPartition, Long> committed = offsets.committed(replayGroup, partitions);
            boolean ready = passed(committed, requiredEventOffsets);
            if (waitListener != null) {
                waitListener.onPoll(committed, elapsedMs(deadline), ready);
            }
            if (ready) {
                return Result.ok();
            }
            long now = nanoTime.getAsLong();
            if (now >= deadline) {
                return Result.timeout(Duration.ofNanos(Math.max(0, now - (deadline - timeout.toNanos()))).toMillis(),
                        describe(committed, requiredEventOffsets));
            }
            try {
                sleeper.sleep(pollInterval);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return Result.timeout(elapsedMs(deadline), "interrupted while waiting for replay");
            }
        }
    }

    static boolean passed(
            Map<TopicPartition, Long> committed, Map<TopicPartition, Long> requiredEventOffsets) {
        for (Map.Entry<TopicPartition, Long> entry : requiredEventOffsets.entrySet()) {
            Long offset = committed.get(entry.getKey());
            // committed is the next offset to read; must be > event offset.
            if (offset == null || offset <= entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private long elapsedMs(long deadline) {
        long started = deadline - timeout.toNanos();
        return Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - started)).toMillis();
    }

    private static String describe(
            Map<TopicPartition, Long> committed, Map<TopicPartition, Long> requiredEventOffsets) {
        StringBuilder text = new StringBuilder("replay lag");
        for (Map.Entry<TopicPartition, Long> entry : requiredEventOffsets.entrySet()) {
            text.append(" ").append(entry.getKey())
                    .append(" need>").append(entry.getValue())
                    .append(" committed=").append(committed.get(entry.getKey()));
        }
        return text.toString();
    }
}
