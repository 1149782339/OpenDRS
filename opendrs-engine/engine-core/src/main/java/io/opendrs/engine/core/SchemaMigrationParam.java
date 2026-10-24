package io.opendrs.engine.core;

import io.opendrs.common.log.MigrationLogListener;
import io.opendrs.engine.replay.mapping.TableSelection;
import io.opendrs.jdbc.JdbcConnectionConfig;
import java.util.Objects;

/**
 * Structure-only migration: create tables, indexes and constraints on the target. No rows move and
 * no CDC starts. Existing target tables are left untouched and reported through
 * {@link SchemaMigrationTask#skipped()}, so the job is idempotent and safe to re-run.
 *
 * <p>{@code null} selection means the whole source. {@code null} listener means
 * {@link MigrationLogListener#NONE}.
 *
 * @param logListener sees per-table DDL progress as it happens; called on the engine worker thread
 */
public record SchemaMigrationParam(
        JdbcConnectionConfig source,
        JdbcConnectionConfig target,
        TableSelection selection,
        MigrationLogListener logListener)
        implements MigrationParam {

    public SchemaMigrationParam {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        logListener = logListener == null ? MigrationLogListener.NONE : logListener;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private JdbcConnectionConfig source;
        private JdbcConnectionConfig target;
        private TableSelection selection;
        private MigrationLogListener logListener;

        private Builder() {
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

        public Builder logListener(MigrationLogListener logListener) {
            this.logListener = logListener;
            return this;
        }

        public SchemaMigrationParam build() {
            return new SchemaMigrationParam(source, target, selection, logListener);
        }
    }
}
