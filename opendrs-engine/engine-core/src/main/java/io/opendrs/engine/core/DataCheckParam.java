package io.opendrs.engine.core;

import io.opendrs.engine.common.log.MigrationLogListener;
import io.opendrs.engine.common.selection.TableSelection;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import java.util.Objects;

/**
 * One data-check run: compare the serialized rows of the selected tables between source and
 * target. {@code null} selection means the whole source.
 */
public record DataCheckParam(
        long taskId,
        JdbcConnectionConfig source,
        JdbcConnectionConfig target,
        TableSelection selection,
        boolean includeFloat,
        MigrationLogListener logListener)
        implements MigrationParam {

    public DataCheckParam {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        logListener = logListener == null ? MigrationLogListener.NONE : logListener;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private long taskId;
        private JdbcConnectionConfig source;
        private JdbcConnectionConfig target;
        private TableSelection selection;
        private boolean includeFloat;
        private MigrationLogListener logListener;

        private Builder() {
        }

        public Builder taskId(long taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder source(JdbcConnectionConfig source) {
            this.source = source;
            return this;
        }

        public Builder target(JdbcConnectionConfig target) {
            this.target = target;
            return this;
        }

        public Builder selection(TableSelection selection) {
            this.selection = selection;
            return this;
        }

        /** Whether FLOAT/DOUBLE columns take part in the comparison; currently ignored. */
        public Builder includeFloat(boolean includeFloat) {
            this.includeFloat = includeFloat;
            return this;
        }

        public Builder logListener(MigrationLogListener logListener) {
            this.logListener = logListener;
            return this;
        }

        public DataCheckParam build() {
            return new DataCheckParam(
                    taskId, source, target, selection, includeFloat, logListener);
        }
    }
}
