package io.opendrs.migration.api.response;

import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.engine.core.TaskState;
import io.opendrs.engine.precheck.PrecheckItem;
import io.opendrs.engine.precheck.PrecheckResults;
import java.util.List;

/**
 * The wizard's checklist as it stands right now.
 *
 * <p>{@code items} is the whole plan from the first publish, so the UI can paint every row before
 * any of them has finished; {@code runningId} names the row that is executing at this instant and is
 * {@code null} between checks. There is deliberately no separate source/target split: each item
 * carries its own {@code side}, and a check may belong to both ends at once.
 */
public record MigrationPrecheckResponse(
        boolean ok,
        MigrationPhase phase,
        TaskState state,
        String profileId,
        List<PrecheckItem> items,
        String runningId
) {

    public static MigrationPrecheckResponse of(
            boolean ok, MigrationPhase phase, TaskState state, PrecheckResults stored) {
        PrecheckResults safe = stored == null ? PrecheckResults.empty() : stored;
        return new MigrationPrecheckResponse(
                ok, phase, state, safe.profileId(), safe.items(), safe.runningId());
    }
}
