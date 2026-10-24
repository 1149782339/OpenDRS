package io.opendrs.engine.core;

import io.opendrs.engine.precheck.PrecheckResults;
import io.opendrs.engine.replay.mapping.TableSelection;
import io.opendrs.jdbc.JdbcConnectionConfig;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * One precheck pass: expand the selection, map targets, then run the direction's checklist.
 *
 * <p>{@code null} selection means the whole source.
 */
public record PrecheckParam(
        JdbcConnectionConfig source,
        JdbcConnectionConfig target,
        TableSelection selection,
        Consumer<PrecheckResults> progressListener,
        boolean bidirectional)
        implements MigrationParam {

    public PrecheckParam {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private JdbcConnectionConfig source;
        private JdbcConnectionConfig target;
        private TableSelection selection;
        private Consumer<PrecheckResults> progressListener;
        private boolean bidirectional;

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

        /**
         * Notified after every state change: once with the full checklist (all rows pending), then
         * with "this row is running", then with its verdict. Called on the engine worker thread, so
         * the listener must not block for long - the service writes one column per call.
         */
        public Builder progressListener(Consumer<PrecheckResults> progressListener) {
            this.progressListener = progressListener;
            return this;
        }

        /**
         * Bidirectional sync (MySQL ↔ MySQL): runs the bidirectional precheck profile — GTID
         * enabled at both ends, distinct server identities, target tables already present.
         */
        public Builder bidirectional(boolean bidirectional) {
            this.bidirectional = bidirectional;
            return this;
        }

        public PrecheckParam build() {
            return new PrecheckParam(source, target, selection, progressListener, bidirectional);
        }
    }
}
