package io.opendrs.datacheck.job;

import io.opendrs.datacheck.domain.DataCheckMode;
import io.opendrs.datacheck.domain.DataCheckPhase;
import io.opendrs.datacheck.domain.DataCheckTableStatus;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.DataCheckTableRow;
import io.opendrs.datacheck.mapper.DataCheckTaskRepository.DataCheckTaskRow;
import io.opendrs.engine.core.TaskState;
import java.util.List;

/**
 * Restarts a data-check task after the process died. Tables that were mid-full-check are marked
 * {@code FAILED}; tables already in the incremental phase are left to resume from
 * {@code datacheck_offsets}. A failed table does not fail its siblings.
 */
public final class DataCheckRecovery {

    public static final String FULL_RESTART_REASON =
            "service restarted during full data check; the full check cannot be resumed mid-way";

    private DataCheckRecovery() {
    }

    public enum Decision {
        /** Register a coordinator and resume (remaining full tables and/or incremental). */
        RESUME,
        /** Nothing left to run; task already landed in a terminal state. */
        IDLE
    }

    /**
     * Applies table-level recovery and lands or prepares the task. Must only be called when there
     * is no live coordinator for {@code task}.
     */
    public static Decision recover(DataCheckTaskRepository repository, DataCheckTaskRow task) {
        if (repository == null || task == null) {
            return Decision.IDLE;
        }
        List<DataCheckTableRow> tables = repository.findTableRows(task.id());
        failFullPhaseTables(repository, task.id(), tables);
        tables = repository.findTableRows(task.id());
        boolean resumeFull = hasPendingFull(tables);
        boolean resumeIncremental = hasResumableIncremental(task.mode(), tables);
        if (resumeFull || resumeIncremental) {
            repository.forceState(task.id(), TaskState.STARTING);
            return Decision.RESUME;
        }
        long failed = tables.stream()
                .filter(table -> table.status() == DataCheckTableStatus.FAILED)
                .count();
        if (failed > 0) {
            repository.markFailed(task.id(), failed + " table(s) failed");
        } else if (task.state() == TaskState.TERMINATING) {
            repository.landTerminated(task.id());
            if (repository.findTask(task.id()) != null
                    && repository.findTask(task.id()).state() != TaskState.TERMINATED) {
                repository.forceState(task.id(), TaskState.TERMINATED);
            }
        } else {
            // STOPPING / RUNNING / STARTING with nothing left to run.
            int landed = repository.landStopped(task.id());
            if (landed == 0) {
                repository.forceState(task.id(), TaskState.STOPPED);
            }
        }
        return Decision.IDLE;
    }

    static void failFullPhaseTables(
            DataCheckTaskRepository repository, long taskId, List<DataCheckTableRow> tables) {
        if (tables == null) {
            return;
        }
        for (DataCheckTableRow table : tables) {
            if (table.phase() == DataCheckPhase.FULL
                    && table.status() == DataCheckTableStatus.RUNNING) {
                repository.markTableFinished(
                        taskId, table.sourceTableId(), DataCheckTableStatus.FAILED, FULL_RESTART_REASON);
            }
        }
    }

    static boolean hasPendingFull(List<DataCheckTableRow> tables) {
        if (tables == null) {
            return false;
        }
        for (DataCheckTableRow table : tables) {
            if (needsFullCheck(table)) {
                return true;
            }
        }
        return false;
    }

    static boolean needsFullCheck(DataCheckTableRow table) {
        if (table == null) {
            return false;
        }
        if (table.status() == DataCheckTableStatus.FAILED
                || table.status() == DataCheckTableStatus.STOPPED) {
            return false;
        }
        if (table.phase() == DataCheckPhase.INCREMENTAL
                || table.status() == DataCheckTableStatus.COMPLETED) {
            return false;
        }
        // PENDING, or FULL that is not already failed (should have been failed above).
        return table.phase() == DataCheckPhase.PENDING || table.phase() == DataCheckPhase.FULL;
    }

    static boolean hasResumableIncremental(DataCheckMode mode, List<DataCheckTableRow> tables) {
        if (tables == null) {
            return false;
        }
        for (DataCheckTableRow table : tables) {
            if (DataCheckCoordinator.eligibleForIncremental(mode, table)) {
                return true;
            }
        }
        return false;
    }
}
