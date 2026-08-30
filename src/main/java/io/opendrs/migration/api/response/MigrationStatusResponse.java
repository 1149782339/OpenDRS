package io.opendrs.migration.api.response;

import io.opendrs.migration.domain.TaskState;

public record MigrationStatusResponse(
        Long id,
        TaskState state,
        Progress progress,
        Offset offset,
        String error
) {
    public record Progress(int tablesTotal, int tablesDone, long rowsDone, Long lagMs) {
    }

    public record Offset(String scn, String gtid) {
        public static Offset empty() {
            return new Offset(null, null);
        }
    }
}
