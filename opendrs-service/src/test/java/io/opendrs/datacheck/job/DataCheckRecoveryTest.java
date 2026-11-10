package io.opendrs.datacheck.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opendrs.datacheck.domain.DataCheckMode;
import io.opendrs.datacheck.domain.DataCheckPhase;
import io.opendrs.datacheck.domain.DataCheckTableStatus;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.DataCheckTableRow;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.DataCheckTaskRow;
import io.opendrs.engine.core.TaskState;
import java.util.List;
import org.junit.jupiter.api.Test;

class DataCheckRecoveryTest {

    @Test
    void fullPhaseTablesFailAndIncrementalResumes() {
        DataCheckTaskRepository repository = mock(DataCheckTaskRepository.class);
        DataCheckTableRow full = row("s.full", DataCheckPhase.FULL, DataCheckTableStatus.RUNNING);
        DataCheckTableRow inc = row("s.inc", DataCheckPhase.INCREMENTAL, DataCheckTableStatus.RUNNING);
        when(repository.findTableRows(1L)).thenReturn(List.of(full, inc));
        DataCheckTaskRow task = task(DataCheckMode.FULL_AND_INCREMENTAL, TaskState.RUNNING);

        DataCheckRecovery.Decision decision = DataCheckRecovery.recover(repository, task);

        assertEquals(DataCheckRecovery.Decision.RESUME, decision);
        verify(repository).markTableFinished(
                eq(1L), eq("s.full"), eq(DataCheckTableStatus.FAILED),
                eq(DataCheckRecovery.FULL_RESTART_REASON));
        verify(repository, never()).markTableFinished(
                eq(1L), eq("s.inc"), eq(DataCheckTableStatus.FAILED), any());
        verify(repository).forceState(1L, TaskState.STARTING);
    }

    @Test
    void onlyFullPhaseLeftIsFailed() {
        DataCheckTaskRepository repository = mock(DataCheckTaskRepository.class);
        DataCheckTableRow full = row("s.full", DataCheckPhase.FULL, DataCheckTableStatus.RUNNING);
        when(repository.findTableRows(2L)).thenReturn(List.of(full), List.of(
                row("s.full", DataCheckPhase.FULL, DataCheckTableStatus.FAILED)));
        DataCheckTaskRow task = task(DataCheckMode.FULL_ONLY, TaskState.RUNNING);

        DataCheckRecovery.Decision decision = DataCheckRecovery.recover(repository, task);

        assertEquals(DataCheckRecovery.Decision.IDLE, decision);
        verify(repository).markFailed(eq(2L), any());
        verify(repository, never()).forceState(eq(2L), eq(TaskState.STARTING));
    }

    @Test
    void needsFullCheckSkipsFailedAndIncremental() {
        assertTrue(DataCheckRecovery.needsFullCheck(
                row("s.p", DataCheckPhase.PENDING, DataCheckTableStatus.PENDING)));
        assertFalse(DataCheckRecovery.needsFullCheck(
                row("s.f", DataCheckPhase.FULL, DataCheckTableStatus.FAILED)));
        assertFalse(DataCheckRecovery.needsFullCheck(
                row("s.i", DataCheckPhase.INCREMENTAL, DataCheckTableStatus.RUNNING)));
    }

    private static DataCheckTaskRow task(DataCheckMode mode, TaskState state) {
        return new DataCheckTaskRow(
                mode == DataCheckMode.FULL_ONLY ? 2L : 1L,
                "pub", 1007L, 8L, "n", mode, state, null, null, null, null, null);
    }

    private static DataCheckTableRow row(String id, DataCheckPhase phase, DataCheckTableStatus status) {
        return new DataCheckTableRow(
                id, id, phase, status, 0L,
                status == DataCheckTableStatus.FAILED ? DataCheckRecovery.FULL_RESTART_REASON : null,
                null, null, null, null, false);
    }
}
