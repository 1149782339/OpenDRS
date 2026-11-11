package io.opendrs.engine.capture;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Holds incremental replay back until the coordinator lets it start.
 *
 * <p>KAFKA transport only: capture keeps producing the stream to the pipeline topic while the
 * target is still being prepared (the plain secondary indexes are rebuilt after the full load
 * and before any change is replayed), and the replay consumer waits here before it subscribes.
 * DIRECT transport does not need it: there the engine is stopped after the snapshot and started
 * again once the target is ready.
 */
public interface ReplayGate {

    /** Always open: replay starts immediately (every run that is not right after a full load). */
    ReplayGate OPEN = timeoutMs -> true;

    /**
     * Waits up to {@code timeoutMs} for the gate to open.
     *
     * @return true when open
     */
    boolean awaitOpen(long timeoutMs) throws InterruptedException;

    /** A gate that stays closed until {@link #open()}. */
    static Latch latch() {
        return new Latch();
    }

    final class Latch implements ReplayGate {

        private final CountDownLatch latch = new CountDownLatch(1);

        public void open() {
            latch.countDown();
        }

        public boolean isOpen() {
            return latch.getCount() == 0;
        }

        @Override
        public boolean awaitOpen(long timeoutMs) throws InterruptedException {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }
}
