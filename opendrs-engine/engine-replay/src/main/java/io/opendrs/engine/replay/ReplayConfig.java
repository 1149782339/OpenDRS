package io.opendrs.engine.replay;

import io.opendrs.engine.replay.connection.ConnectionProvider;
import io.opendrs.common.naming.ColumnNamingStrategy;
import io.opendrs.engine.replay.metrics.ReplayDirection;
import io.opendrs.engine.replay.metrics.ReplayMetrics;
import io.opendrs.engine.replay.naming.DefaultColumnNamingStrategy;
import io.opendrs.engine.replay.naming.DefaultTableNamingStrategy;
import io.opendrs.common.naming.TableNamingStrategy;
import java.util.Objects;

/**
 * Embedded replay configuration; the applier takes a connection callback instead of a JDBC URL.
 */
public final class ReplayConfig {

    public static final String POSTGRES_DIALECT_NAME = "PostgreSqlDialect";
    public static final String MYSQL_DIALECT_NAME = "MysqlDialect";

    /** Dialect matching the target database. */
    public static String dialectNameFor(io.opendrs.common.metadata.DatabaseType targetType) {
        return switch (targetType) {
            case POSTGRESQL -> POSTGRES_DIALECT_NAME;
            case MYSQL -> MYSQL_DIALECT_NAME;
            case ORACLE -> throw new IllegalArgumentException("No sink dialect for target " + targetType);
        };
    }

    /**
     * Default worker count when the caller does not specify one: a fixed 8 keeps behaviour
     * predictable and is overridden at wiring time by the task options ({@code incremental.replayConcurrency} / {@code full.importConcurrency}).
     */
    public static final int DEFAULT_APPLIER_CONCURRENCY = 8;

    private final ConnectionProvider connectionProvider;
    private final TableNamingStrategy tableNamingStrategy;
    private final ColumnNamingStrategy columnNamingStrategy;
    private final boolean applierDDLEnabled;
    private final int connectionRetriesMax;
    private final int connectionBackoff;
    private final String databaseDialectName;
    /** Owning migration task; the tag metrics are recorded under. */
    private final Long taskId;
    /** Which physical direction this pipeline carries; tags the metrics. */
    private final ReplayDirection direction;
    private final ReplayMetrics metrics;
    /**
     * Bidirectional loop prevention; null (or {@link GtidLoopConfig#taggingEnabled()} false) keeps
     * the single-direction behaviour — no GTID tagging, no capture filtering.
     */
    private final GtidLoopConfig gtidLoop;
    /**
     * Worker count of {@link io.opendrs.engine.replay.applier.TransactionApplier} (incremental
     * replay). 1 means serial per-transaction replay.
     */
    private final int txApplierConcurrency;
    /**
     * Worker count of {@link io.opendrs.engine.replay.applier.TableApplier} (full-load /
     * snapshot rows).
     */
    private final int tableApplierConcurrency;
    /** Queue between the transaction assembler and the dispatcher. */
    private final int transactionBufferSize;
    /** Per-worker queue depth; the dispatcher's load-balancing signal. */
    private final int applierWorkerBufferSize;

    private ReplayConfig(Builder builder) {
        this.connectionProvider = Objects.requireNonNull(builder.connectionProvider, "connectionProvider");
        this.tableNamingStrategy = builder.tableNamingStrategy == null
                ? new DefaultTableNamingStrategy()
                : builder.tableNamingStrategy;
        this.columnNamingStrategy = builder.columnNamingStrategy == null
                ? new DefaultColumnNamingStrategy()
                : builder.columnNamingStrategy;
        this.applierDDLEnabled = builder.applierDDLEnabled;
        this.connectionRetriesMax = builder.connectionRetriesMax;
        this.connectionBackoff = builder.connectionBackoff;
        this.databaseDialectName = builder.databaseDialectName == null
                ? POSTGRES_DIALECT_NAME
                : builder.databaseDialectName;
        this.taskId = builder.taskId;
        this.direction = builder.direction == null ? ReplayDirection.FORWARD : builder.direction;
        this.metrics = builder.metrics == null ? ReplayMetrics.NONE : builder.metrics;
        this.gtidLoop = builder.gtidLoop;
        this.txApplierConcurrency = builder.txApplierConcurrency;
        this.tableApplierConcurrency = builder.tableApplierConcurrency;
        this.transactionBufferSize = builder.transactionBufferSize;
        this.applierWorkerBufferSize = builder.applierWorkerBufferSize;
    }

    public static Builder builder() {
        return new Builder();
    }

    public ConnectionProvider getConnectionProvider() {
        return connectionProvider;
    }

    public TableNamingStrategy getTableNamingStrategy() {
        return tableNamingStrategy;
    }

    public ColumnNamingStrategy getColumnNamingStrategy() {
        return columnNamingStrategy;
    }

    public boolean getApplierDDLEnabled() {
        return applierDDLEnabled;
    }

    public int getConnectionRetriesMax() {
        return connectionRetriesMax;
    }

    public int getConnectionBackoff() {
        return connectionBackoff;
    }

    public String getDatabaseDialectName() {
        return databaseDialectName;
    }

    public Long getTaskId() {
        return taskId;
    }

    /** Which physical direction this pipeline carries. */
    public ReplayDirection getDirection() {
        return direction;
    }

    public ReplayMetrics getMetrics() {
        return metrics;
    }

    /** Bidirectional loop config; may be null when tagging is not configured. */
    public GtidLoopConfig getGtidLoop() {
        return gtidLoop;
    }

    /** Worker count of the incremental (per-transaction) applier. */
    public int getTxApplierConcurrency() {
        return txApplierConcurrency;
    }

    /** Worker count of the full-load (per-table) applier. */
    public int getTableApplierConcurrency() {
        return tableApplierConcurrency;
    }

    /** Queue depth between transaction assembly and dispatch. */
    public int getTransactionBufferSize() {
        return transactionBufferSize;
    }

    /** Per-worker queue depth used by the dispatcher's load balancing. */
    public int getApplierWorkerBufferSize() {
        return applierWorkerBufferSize;
    }

    public static final class Builder {
        private ConnectionProvider connectionProvider;
        private TableNamingStrategy tableNamingStrategy;
        private ColumnNamingStrategy columnNamingStrategy;
        private boolean applierDDLEnabled = true;
        private int connectionRetriesMax = 5;
        private int connectionBackoff = 3000;
        private String databaseDialectName = POSTGRES_DIALECT_NAME;
        private Long taskId;
        private ReplayDirection direction;
        private ReplayMetrics metrics;
        private GtidLoopConfig gtidLoop;
        private int txApplierConcurrency = DEFAULT_APPLIER_CONCURRENCY;
        private int tableApplierConcurrency = DEFAULT_APPLIER_CONCURRENCY;
        private int transactionBufferSize = 20;
        private int applierWorkerBufferSize = 100;

        private Builder() {
        }

        public Builder connectionProvider(ConnectionProvider connectionProvider) {
            this.connectionProvider = connectionProvider;
            return this;
        }

        public Builder tableNamingStrategy(TableNamingStrategy tableNamingStrategy) {
            this.tableNamingStrategy = tableNamingStrategy;
            return this;
        }

        public Builder columnNamingStrategy(ColumnNamingStrategy columnNamingStrategy) {
            this.columnNamingStrategy = columnNamingStrategy;
            return this;
        }

        public Builder applyDdlEnabled(boolean applierDDLEnabled) {
            this.applierDDLEnabled = applierDDLEnabled;
            return this;
        }

        public Builder connectionRetriesMax(int connectionRetriesMax) {
            this.connectionRetriesMax = connectionRetriesMax;
            return this;
        }

        public Builder connectionBackoffMs(int connectionBackoff) {
            this.connectionBackoff = connectionBackoff;
            return this;
        }

        public Builder databaseDialectName(String databaseDialectName) {
            this.databaseDialectName = databaseDialectName;
            return this;
        }

        public Builder taskId(Long taskId) {
            this.taskId = taskId;
            return this;
        }

        public Builder direction(ReplayDirection direction) {
            this.direction = direction;
            return this;
        }

        public Builder metrics(ReplayMetrics metrics) {
            this.metrics = metrics;
            return this;
        }

        public Builder gtidLoop(GtidLoopConfig gtidLoop) {
            this.gtidLoop = gtidLoop;
            return this;
        }

        public Builder txApplierConcurrency(int txApplierConcurrency) {
            this.txApplierConcurrency = txApplierConcurrency;
            return this;
        }

        public Builder tableApplierConcurrency(int tableApplierConcurrency) {
            this.tableApplierConcurrency = tableApplierConcurrency;
            return this;
        }

        public Builder transactionBufferSize(int transactionBufferSize) {
            this.transactionBufferSize = transactionBufferSize;
            return this;
        }

        public Builder applierWorkerBufferSize(int applierWorkerBufferSize) {
            this.applierWorkerBufferSize = applierWorkerBufferSize;
            return this;
        }

        public ReplayConfig build() {
            return new ReplayConfig(this);
        }
    }
}
