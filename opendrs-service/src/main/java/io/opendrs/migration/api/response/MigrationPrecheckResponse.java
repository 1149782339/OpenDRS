package io.opendrs.migration.api.response;

import io.opendrs.migration.domain.JobPhase;
import io.opendrs.migration.domain.JobState;
import io.opendrs.precheck.CheckResult;
import io.opendrs.precheck.PrecheckResults;
import java.util.List;

public record MigrationPrecheckResponse(
        boolean ok,
        JobPhase jobPhase,
        JobState jobState,
        List<CheckResult> results,
        List<CheckResult> sourceResults,
        List<CheckResult> targetResults
) {

    public static MigrationPrecheckResponse of(
            boolean ok, JobPhase jobPhase, JobState jobState, PrecheckResults stored) {
        PrecheckResults safe = stored == null ? PrecheckResults.empty() : stored;
        return new MigrationPrecheckResponse(
                ok, jobPhase, jobState, safe.all(), safe.source(), safe.target());
    }
}
