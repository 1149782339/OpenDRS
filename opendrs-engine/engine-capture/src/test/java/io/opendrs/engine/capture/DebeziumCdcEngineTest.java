package io.opendrs.engine.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.debezium.engine.DebeziumEngine;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Covers the release contract of {@link DebeziumCdcEngineFactory.DebeziumCdcEngine}: the metadata
 * binding must be undone exactly once, whether the run ends on its own, is stopped from another
 * thread, or is stopped before it ever started.
 */
class DebeziumCdcEngineTest {

    private final AtomicInteger releases = new AtomicInteger();

    @Test
    void stopReleasesExactlyOnceEvenWhenCalledTwice() {
        FakeDebezium debezium = new FakeDebezium();
        DebeziumCdcEngineFactory.DebeziumCdcEngine engine = engine(debezium, new AtomicReference<>());

        engine.stop();
        engine.stop();

        assertThat(releases.get()).isEqualTo(1);
    }

    @Test
    void runReleasesOnceAndRethrowsTheEngineFailure() {
        FakeDebezium debezium = new FakeDebezium();
        AtomicReference<Throwable> error = new AtomicReference<>(new IllegalStateException("binlog gone"));
        DebeziumCdcEngineFactory.DebeziumCdcEngine engine = engine(debezium, error);

        assertThatThrownBy(engine::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("binlog gone");

        // The normal stop path: close() returns, then run() falls out of its finally.
        engine.stop();
        assertThat(releases.get()).isEqualTo(1);
        assertThat(debezium.ran).isTrue();
    }

    @Test
    void stopBeforeRunSwallowsNotStartedAndStillReleases() {
        FakeDebezium debezium = new FakeDebezium();
        debezium.closeThrowsIllegalState = true;
        DebeziumCdcEngineFactory.DebeziumCdcEngine engine = engine(debezium, new AtomicReference<>());

        engine.stop();

        assertThat(releases.get()).isEqualTo(1);
        assertThat(debezium.ran).isFalse();
    }

    private DebeziumCdcEngineFactory.DebeziumCdcEngine engine(
            DebeziumEngine<Object> debezium, AtomicReference<Throwable> error) {
        return new DebeziumCdcEngineFactory.DebeziumCdcEngine(
                debezium, error, 7L, null, releases::incrementAndGet);
    }

    /** Minimal stand-in: the factory only ever calls {@code run()} and {@code close()}. */
    static final class FakeDebezium implements DebeziumEngine<Object> {

        volatile boolean ran;
        boolean closeThrowsIllegalState;

        @Override
        public void run() {
            ran = true;
        }

        @Override
        public void close() throws IOException {
            if (closeThrowsIllegalState) {
                throw new IllegalStateException("Engine is not running");
            }
        }
    }
}
