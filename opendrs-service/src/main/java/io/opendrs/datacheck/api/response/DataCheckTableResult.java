package io.opendrs.datacheck.api.response;

import io.opendrs.datacheck.domain.DataCheckPhase;
import io.opendrs.datacheck.domain.DataCheckTableStatus;
import java.time.Instant;

/**
 * One table's state and verdict inside a check task.
 *
 * @param sourceTableId source identifier ({@code schema.table})
 * @param targetTableId target identifier, resolved at creation
 * @param differentCount rows present on both sides but different
 * @param sourceOnlyCount rows only on the source
 * @param targetOnlyCount rows only on the target
 * @param totalCount sum of the three
 * @param rowsChecked source rows read during the full check, if known
 * @param lastCheckAt last time this table was checked (full pass or incremental batch)
 * @param waitingForReplay incremental check is waiting for replay to catch up
 */
public record DataCheckTableResult(
        String sourceTableId,
        String targetTableId,
        DataCheckPhase phase,
        DataCheckTableStatus status,
        long lagMs,
        String errorMessage,
        long differentCount,
        long sourceOnlyCount,
        long targetOnlyCount,
        long totalCount,
        Long rowsChecked,
        Instant lastCheckAt,
        boolean waitingForReplay) {
}
