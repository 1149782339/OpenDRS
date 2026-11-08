package io.opendrs.migration.job;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.migration.domain.MigrationMode;
import org.junit.jupiter.api.Test;

class MigrationCoordinatorRegistryTest {

    @Test
    void putIfAbsentOccupiesSlotBeforeThreadStarts() {
        MigrationCoordinatorRegistry registry = new MigrationCoordinatorRegistry();
        MigrationCoordinator first = new MigrationCoordinator(1L, MigrationMode.FULL_AND_INCREMENTAL, null, registry);
        MigrationCoordinator second = new MigrationCoordinator(1L, MigrationMode.FULL_AND_INCREMENTAL, null, registry);

        assertThat(registry.tryRegister(first)).isTrue();
        assertThat(registry.hasLive(1L)).isTrue();
        assertThat(registry.tryRegister(second)).isFalse();
    }
}
