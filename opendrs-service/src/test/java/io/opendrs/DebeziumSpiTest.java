package io.opendrs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.debezium.spi.snapshot.Snapshotter;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/**
 * Guards the Debezium SPI registration that ships in the application classpath.
 *
 * <p>Debezium resolves custom snapshot modes with {@link ServiceLoader}, so a
 * {@code META-INF/services} file naming a class that no longer exists does not fail at compile time
 * — it fails when the first Engine starts, with {@code ServiceConfigurationError: Provider ...
 * not found}, and only in the packaged application. That is exactly what happened when the capture
 * package moved to {@code io.opendrs.engine.capture}: the registration stayed in the service module
 * and kept naming {@code io.opendrs.debezium.*}.
 */
class DebeziumSpiTest {

    @Test
    void everyRegisteredSnapshotterResolves() {
        assertThatCode(() -> ServiceLoader.load(Snapshotter.class).stream()
                        .forEach(provider -> provider.get().name()))
                .doesNotThrowAnyException();
    }

    @Test
    void engineSnapshottersAreRegistered() {
        List<String> names = ServiceLoader.load(Snapshotter.class).stream()
                .map(provider -> provider.get().name())
                .toList();

        assertThat(names).contains("opendrs_schema", "opendrs_incremental");
    }
}
