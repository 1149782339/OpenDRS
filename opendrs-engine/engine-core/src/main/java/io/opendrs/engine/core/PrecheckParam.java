package io.opendrs.engine.core;

import io.opendrs.engine.precheck.PrecheckResults;
import io.opendrs.engine.common.selection.TableSelection;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
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
        boolean bidirectional,
        boolean deferredObjects)
        implements MigrationParam {

    public PrecheckParam {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
    }

    public PrecheckParam(
            JdbcConnectionConfig source,
            JdbcConnectionConfig target,
            TableSelection selection,
            Consumer<PrecheckResults> progressListener,
            boolean bidirectional) {
        this(source, target, selection, progressListener, bidirectional, false);
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
        private boolean deferredObjects;

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

        /**
         * The task runs the deferred-target-objects flow (one-way full + incremental): binds the
         * checks for non-empty target tables, outside foreign keys, cascading source foreign keys
         * and the target's right to switch FK enforcement off.
         */
        public Builder deferredObjects(boolean deferredObjects) {
            this.deferredObjects = deferredObjects;
            return this;
        }

        public PrecheckParam build() {
            return new PrecheckParam(source, target, selection, progressListener, bidirectional, deferredObjects);
        }
    }
}
