package io.opendrs.engine.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.engine.capture.CdcEngine;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Locks in the phase contract the coordinator relies on: {@link DataMigrationTask#taskPhase()} is
 * never empty — it reads {@code FULL} from the moment the handle exists and moves to
 * {@code INCREMENTAL} once the snapshot is done.
 */
class DefaultDataMigrationTaskTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void phaseIsFullBeforeStartAndIncrementalAfterSnapshot() throws Exception {
        CountDownLatch incremental = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);

        DefaultDataMigrationTask task = new DefaultDataMigrationTask(
                1L,
                onSnapshot -> new BlockingCdcEngine(onSnapshot, stopped),
                phase -> {
                    if (phase == DataMigrationTaskPhase.INCREMENTAL) {
                        incremental.countDown();
                    }
                });

        // Before the worker thread exists, the run is already defined as "full load first".
        assertThat(task.state()).isEqualTo(TaskState.STARTING);
        assertThat(task.taskPhase()).isEqualTo(DataMigrationTaskPhase.FULL);

        task.start(executor);
        assertThat(incremental.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(task.taskPhase()).isEqualTo(DataMigrationTaskPhase.INCREMENTAL);

        task.requestStop();
        task.await();
        assertThat(task.state()).isEqualTo(TaskState.STOPPED);
    }

    /** Runs the snapshot callback, then blocks until stopped, like the real engine. */
    private static final class BlockingCdcEngine implements CdcEngine {

        private final Runnable onSnapshot;
        private final CountDownLatch stopped;

        BlockingCdcEngine(Runnable onSnapshot, CountDownLatch stopped) {
            this.onSnapshot = onSnapshot;
            this.stopped = stopped;
        }

        @Override
        public void run() {
            onSnapshot.run();
            try {
                stopped.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void stop() {
            stopped.countDown();
        }
    }
}
