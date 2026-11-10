package io.opendrs.engine.schema.imports;

import io.opendrs.engine.common.naming.SchemaNamingStrategy;

/**
 * Options for one import batch.
 *
 * <p>{@link #defaults()} — no schema remapping, no statement timeout — is what
 * {@link DefaultObjectImporter} assumes when it is handed {@code null}.
 */
public class ImportConfiguration {

    private final SchemaNamingStrategy schemaNamingStrategy;
    private final int statementTimeoutSeconds;
    private final ImportListener listener;

    private ImportConfiguration(Builder builder) {
        this.schemaNamingStrategy = builder.schemaNamingStrategy;
        this.statementTimeoutSeconds = builder.statementTimeoutSeconds;
        this.listener = builder.listener;
    }

    public static ImportConfiguration defaults() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Maps the schema an object came from onto the schema it should be created in, or {@code null}
     * (the default) to leave the connection's current schema alone.
     *
     * <p>Statements are executed verbatim, so a converted object whose DDL carries no schema
     * qualifier is created wherever the connection points. When a strategy is supplied the importer
     * resolves {@code source object's schema -> target schema} before each object and points the
     * connection at the result ({@code Connection.setSchema}), switching only when it changes.
     * Qualified DDL is unaffected — the qualifier wins over the connection's current schema.
     *
     * <p>The importer does not create the target schema; the caller guarantees it exists.
     */
    public SchemaNamingStrategy getSchemaNamingStrategy() {
        return schemaNamingStrategy;
    }

    /**
     * Per-statement timeout in seconds; {@code 0} (the default) means no timeout. Structure DDL can
     * block behind a lock on the target, so a hung statement would otherwise pin the whole batch.
     */
    public int getStatementTimeoutSeconds() {
        return statementTimeoutSeconds;
    }

    /** Notified per object, in execution order; {@link ImportListener#NONE} by default. */
    public ImportListener getListener() {
        return listener;
    }

    public static class Builder {
        private SchemaNamingStrategy schemaNamingStrategy;
        private int statementTimeoutSeconds;
        private ImportListener listener = ImportListener.NONE;

        public Builder schemaNamingStrategy(SchemaNamingStrategy schemaNamingStrategy) {
            this.schemaNamingStrategy = schemaNamingStrategy;
            return this;
        }

        public Builder statementTimeoutSeconds(int seconds) {
            this.statementTimeoutSeconds = seconds;
            return this;
        }

        public Builder listener(ImportListener listener) {
            this.listener = listener == null ? ImportListener.NONE : listener;
            return this;
        }

        public ImportConfiguration build() {
            return new ImportConfiguration(this);
        }
    }
}
