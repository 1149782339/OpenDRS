package io.opendrs.migration.api.response;

import io.opendrs.migration.domain.MigrationPhase;
import io.opendrs.engine.core.TaskState;

public record MigrationStatusResponse(
        Long id,
        MigrationPhase phase,
        TaskState state,
        Progress progress,
        Offset offset,
        /** Reverse direction (bidirectional sync only); null for single-direction tasks. */
        Offset reverse,
        String error
) {
    public record Progress(Long lagMs) {
    }

    /**
     * Binlog positions along the pipeline, all in "mysql-bin.000007:197" form.
     *
     * @param source where the source has written to (queried live, cached a few seconds);
     *     null when the source is unreachable
     * @param captured how far Debezium has read; null before the first streaming batch
     * @param applied how far the target has committed; null before the first applied batch
     * @param updatedAt when the position last moved, "yyyy-MM-dd HH:mm:ss"
     */
    public record Offset(String source, String captured, String applied, String updatedAt) {

        /** True when nothing was ever reported for this direction; the UI renders a dash. */
        public boolean isEmpty() {
            return source == null && captured == null && applied == null;
        }
    }
}
