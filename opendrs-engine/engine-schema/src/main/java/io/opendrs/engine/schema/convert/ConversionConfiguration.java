package io.opendrs.engine.schema.convert;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.common.naming.ColumnNamingStrategy;
import io.opendrs.common.naming.TableNamingStrategy;

/**
 * Conversion configuration used by sql converter.
 *
 * <p>Scope is one source statement: the converter turns a single {@code CREATE TABLE} into its
 * target equivalent, so the switches below are per-statement, not per-batch. That is why the
 * primary key flag is singular — a table has at most one — while unique keys, indexes, foreign keys
 * and comments are plural, since one statement can carry several of each.
 */
public class ConversionConfiguration {
    private final ColumnNamingStrategy columnNamingStrategy;
    private final TableNamingStrategy tableNamingStrategy;
    private final DatabaseType sourceDatabase;
    private final DatabaseType targetDatabase;
    private final boolean includePrimaryKey;
    private final boolean includeUniqueKeys;
    private final boolean includeSecondaryIndexes;
    private final boolean includeForeignKeys;
    private final boolean includeComments;

    public ConversionConfiguration(TableNamingStrategy tableNamingStrategy, ColumnNamingStrategy columnNamingStrategy) {
        this(tableNamingStrategy, columnNamingStrategy, null, null);
    }

    public ConversionConfiguration(
            TableNamingStrategy tableNamingStrategy,
            ColumnNamingStrategy columnNamingStrategy,
            DatabaseType sourceDatabase,
            DatabaseType targetDatabase) {
        this.tableNamingStrategy = tableNamingStrategy;
        this.columnNamingStrategy = columnNamingStrategy;
        this.sourceDatabase = sourceDatabase;
        this.targetDatabase = targetDatabase;
        this.includePrimaryKey = true;
        this.includeUniqueKeys = true;
        this.includeSecondaryIndexes = true;
        this.includeForeignKeys = true;
        this.includeComments = true;
    }

    private ConversionConfiguration(Builder builder) {
        this.tableNamingStrategy = builder.tableNamingStrategy;
        this.columnNamingStrategy = builder.columnNamingStrategy;
        this.sourceDatabase = builder.sourceDatabase;
        this.targetDatabase = builder.targetDatabase;
        this.includePrimaryKey = builder.includePrimaryKey;
        this.includeUniqueKeys = builder.includeUniqueKeys;
        this.includeSecondaryIndexes = builder.includeSecondaryIndexes;
        this.includeForeignKeys = builder.includeForeignKeys;
        this.includeComments = builder.includeComments;
    }

    public static Builder builder(
            TableNamingStrategy tableNamingStrategy, ColumnNamingStrategy columnNamingStrategy) {
        return new Builder(tableNamingStrategy, columnNamingStrategy);
    }

    public ColumnNamingStrategy getColumnNamingStrategy() {
        return columnNamingStrategy;
    }

    public TableNamingStrategy getTableNamingStrategy() {
        return tableNamingStrategy;
    }

    public DatabaseType getSourceDatabase() {
        return sourceDatabase;
    }

    public DatabaseType getTargetDatabase() {
        return targetDatabase;
    }

    /**
     * Whether the target table keeps the source's primary key. Defaults to {@code true}.
     *
     * <p>Turning it off is rarely what you want: the incremental replay addresses rows by primary
     * key, so a table without one cannot be updated or upserted once the load is running.
     */
    public boolean includePrimaryKey() {
        return includePrimaryKey;
    }

    /** Whether unique keys are created. Defaults to {@code true}. */
    public boolean includeUniqueKeys() {
        return includeUniqueKeys;
    }

    /**
     * Whether non-unique (secondary) indexes are created. Defaults to {@code true}.
     *
     * <p>Turning it off speeds up a bulk load and costs query performance later; the indexes have
     * to be created by hand afterwards.
     */
    public boolean includeSecondaryIndexes() {
        return includeSecondaryIndexes;
    }

    /**
     * Whether the converted batch keeps the source's foreign keys, as separate
     * {@code ALTER TABLE ... ADD CONSTRAINT ... FOREIGN KEY} statements. Defaults to {@code true}.
     *
     * <p>Turn it off for a table-only pass — the one a data migration needs when the target tables
     * do not exist yet. Foreign keys constrain the order rows can be loaded in and make a bulk load
     * fail on rows that would be valid once the whole batch is in.
     */
    public boolean includeForeignKeys() {
        return includeForeignKeys;
    }

    /**
     * Whether table and column comments are carried over, as {@code COMMENT ON} statements.
     * Defaults to {@code true}.
     */
    public boolean includeComments() {
        return includeComments;
    }

    public static final class Builder {

        private final TableNamingStrategy tableNamingStrategy;
        private final ColumnNamingStrategy columnNamingStrategy;
        private DatabaseType sourceDatabase;
        private DatabaseType targetDatabase;
        private boolean includePrimaryKey = true;
        private boolean includeUniqueKeys = true;
        private boolean includeSecondaryIndexes = true;
        private boolean includeForeignKeys = true;
        private boolean includeComments = true;

        private Builder(
                TableNamingStrategy tableNamingStrategy, ColumnNamingStrategy columnNamingStrategy) {
            this.tableNamingStrategy = tableNamingStrategy;
            this.columnNamingStrategy = columnNamingStrategy;
        }

        public Builder databases(DatabaseType source, DatabaseType target) {
            this.sourceDatabase = source;
            this.targetDatabase = target;
            return this;
        }

        public Builder includePrimaryKey(boolean includePrimaryKey) {
            this.includePrimaryKey = includePrimaryKey;
            return this;
        }

        public Builder includeUniqueKeys(boolean includeUniqueKeys) {
            this.includeUniqueKeys = includeUniqueKeys;
            return this;
        }

        public Builder includeSecondaryIndexes(boolean includeSecondaryIndexes) {
            this.includeSecondaryIndexes = includeSecondaryIndexes;
            return this;
        }

        public Builder includeForeignKeys(boolean includeForeignKeys) {
            this.includeForeignKeys = includeForeignKeys;
            return this;
        }

        public Builder includeComments(boolean includeComments) {
            this.includeComments = includeComments;
            return this;
        }

        public ConversionConfiguration build() {
            return new ConversionConfiguration(this);
        }
    }
}
