package io.opendrs.migration.job;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.migration.domain.MigrationMode;
import org.junit.jupiter.api.Test;

class TaskJobRegistryTest {

    @Test
    void putIfAbsentOccupiesSlotBeforeThreadStarts() {
        TaskJobRegistry registry = new TaskJobRegistry();
        TaskJob first = new TaskJob(1L, MigrationMode.FULL_AND_INCREMENTAL, null, registry);
        TaskJob second = new TaskJob(1L, MigrationMode.FULL_AND_INCREMENTAL, null, registry);

        assertThat(registry.tryRegister(first)).isTrue();
        assertThat(registry.hasLive(1L)).isTrue();
        assertThat(registry.tryRegister(second)).isFalse();
    }
}
