package io.opendrs.engine.schema.export;

import io.opendrs.common.selection.TableSelection;

public class ExportConfiguration {
    private boolean includeViews;
    private boolean includeTriggers;
    private boolean ignoreForeignKey;
    private TableSelection selection;

    private ExportConfiguration() {

    }

    public boolean includeViews() {
        return includeViews;
    }

    public boolean includeTriggers() {
        return includeTriggers;
    }

    public boolean ignoreForeignKey() {
        return ignoreForeignKey;
    }

    public TableSelection getSelection() {
        return selection;
    }

    public static class Builder {
        ExportConfiguration configuration = new ExportConfiguration();

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

        public Builder ignoreForeignKey() {
            configuration.ignoreForeignKey = true;
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
