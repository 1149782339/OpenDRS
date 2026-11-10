package io.opendrs.datacheck.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opendrs.common.error.AppException;
import io.opendrs.datacheck.domain.DataCheckPhase;
import io.opendrs.datacheck.domain.DataCheckTableStatus;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.DataCheckTableRow;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.MigrationTaskRow;
import io.opendrs.engine.core.TaskState;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataCheckPolicyTest {

    @Test
    void creationRejectedUntilFullMigrationCompletes() {
        assertThrows(AppException.class, () -> DataCheckTaskService.validateMigrationReady(
                migration("FULL_AND_INCREMENTAL", "FULL", "RUNNING")));
        assertThrows(AppException.class, () -> DataCheckTaskService.validateMigrationReady(
                migration("FULL_ONLY", "FULL", "RUNNING")));
        DataCheckTaskService.validateMigrationReady(
                migration("FULL_AND_INCREMENTAL", "INCREMENTAL", "RUNNING"));
        DataCheckTaskService.validateMigrationReady(
                migration("INCREMENTAL", "INCREMENTAL", "RUNNING"));
        DataCheckTaskService.validateMigrationReady(
                migration("FULL_ONLY", "FULL", "COMPLETED"));
    }

    @Test
    void stopRejectedDuringFullPhase() {
        DataCheckTableRow fullRunning = row(DataCheckPhase.FULL, DataCheckTableStatus.RUNNING);
        DataCheckTableRow inc = row(DataCheckPhase.INCREMENTAL, DataCheckTableStatus.RUNNING);
        assertTrue(DataCheckTaskService.isFullCheckRunning(List.of(fullRunning, inc)));
        assertFalse(DataCheckTaskService.isStoppable(TaskState.RUNNING, List.of(fullRunning)));
        assertTrue(DataCheckTaskService.isStoppable(TaskState.RUNNING, List.of(inc)));
        assertFalse(DataCheckTaskService.isStoppable(TaskState.STOPPED, List.of(inc)));
    }

    private static MigrationTaskRow migration(String mode, String phase, String state) {
        return new MigrationTaskRow(1007L, 17L, 18L, "{}", mode, phase, state);
    }

    private static DataCheckTableRow row(DataCheckPhase phase, DataCheckTableStatus status) {
        return new DataCheckTableRow(
                "s.t", "s.t", phase, status, 0L, null, null, null, null, null, false);
    }
}
