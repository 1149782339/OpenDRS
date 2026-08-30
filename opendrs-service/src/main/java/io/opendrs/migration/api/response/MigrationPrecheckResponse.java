package io.opendrs.migration.api.response;

import io.opendrs.migration.domain.JobPhase;
import io.opendrs.migration.domain.JobState;
import io.opendrs.precheck.CheckResult;
import java.util.List;

public record MigrationPrecheckResponse(
        boolean ok,
        JobPhase jobPhase,
        JobState jobState,
        List<CheckResult> results
) {
}
