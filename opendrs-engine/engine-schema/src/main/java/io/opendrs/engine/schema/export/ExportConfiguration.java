package io.opendrs.engine.schema.export;

import io.opendrs.engine.common.selection.TableSelection;

/**
 * What to read from the source, and which structural elements the batch should carry.
 *
 * <p>Export itself only consumes {@link #includeForeignKeys()} — the other three switches are read
 * by the conversion stage. They live here so one configuration describes the whole run: the
 * schema migration assembles export and convert from the same object, instead of the caller
 * having to keep two sets of flags in sync.
 */
public class ExportConfiguration {
    private boolean includeViews;
    private boolean includeTriggers;
    private boolean includePrimaryKey = true;
    private boolean includeUniqueKeys = true;
    private boolean includeSecondaryIndexes = true;
    private boolean includeForeignKeys = true;
    private TableSelection selection;

    private ExportConfiguration() {

    }

    public boolean includeViews() {
        return includeViews;
    }

    public boolean includeTriggers() {
        return includeTriggers;
    }

    /**
     * Whether foreign keys are read as dependencies, and later created as constraints.
     *
     * <p>Export uses this to skip the foreign key queries entirely when the answer is no: a batch
     * that will not carry the constraints does not need their dependency edges either.
     */
    public boolean includeForeignKeys() {
        return includeForeignKeys;
    }

    public boolean includePrimaryKey() {
        return includePrimaryKey;
    }

    public boolean includeUniqueKeys() {
        return includeUniqueKeys;
    }

    public boolean includeSecondaryIndexes() {
        return includeSecondaryIndexes;
    }

    public TableSelection getSelection() {
        return selection;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private final ExportConfiguration configuration = new ExportConfiguration();

        public Builder includeViews() {
            configuration.includeViews = true;
            return this;
        }

        public Builder includeTriggers() {
            configuration.includeTriggers = true;
            return this;
        }

        public Builder includeAll() {
            configuration.includeViews = true;
            configuration.includeTriggers = true;
            return this;
        }

        public Builder includePrimaryKey(boolean includePrimaryKey) {
            configuration.includePrimaryKey = includePrimaryKey;
            return this;
        }

        public Builder includeUniqueKeys(boolean includeUniqueKeys) {
            configuration.includeUniqueKeys = includeUniqueKeys;
            return this;
        }

        public Builder includeSecondaryIndexes(boolean includeSecondaryIndexes) {
            configuration.includeSecondaryIndexes = includeSecondaryIndexes;
            return this;
        }

        public Builder includeForeignKeys(boolean includeForeignKeys) {
            configuration.includeForeignKeys = includeForeignKeys;
            return this;
        }

        public Builder selection(TableSelection selection) {
            configuration.selection = selection;
            return this;
        }

        public ExportConfiguration build() {
            return configuration;
        }
    }
}
