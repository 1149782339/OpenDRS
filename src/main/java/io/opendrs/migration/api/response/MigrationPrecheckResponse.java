package io.opendrs.migration.api.response;

import io.opendrs.migration.domain.TaskState;
import io.opendrs.precheck.CheckResult;
import java.util.List;

public record MigrationPrecheckResponse(
        boolean ok,
        TaskState state,
        List<CheckResult> results
) {
}
