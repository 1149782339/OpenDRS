package io.opendrs.sink;

import io.opendrs.sink.connection.ConnectionProvider;
import io.opendrs.sink.naming.ColumnNamingStrategy;
import io.opendrs.sink.naming.DefaultColumnNamingStrategy;
import io.opendrs.sink.naming.DefaultTableNamingStrategy;
import io.opendrs.sink.naming.TableNamingStrategy;
import java.util.Objects;

/**
 * Embedded sink configuration. Replaces DbSink {@code ConnectorConfig} / Kafka Connect
 * {@code AbstractConfig} so the applier can take a connection callback instead of a JDBC URL.
 */
public final class SinkConfig {

    public static final String POSTGRES_DIALECT_NAME = "PostgreSqlDialect";

    private final ConnectionProvider connectionProvider;
    private final TableNamingStrategy tableNamingStrategy;
    private final ColumnNamingStrategy columnNamingStrategy;
    private final boolean applierDDLEnabled;
    private final int connectionRetriesMax;
    private final int connectionBackoff;
    private final String databaseDialectName;

    private SinkConfig(Builder builder) {
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

    public static final class Builder {
        private ConnectionProvider connectionProvider;
        private TableNamingStrategy tableNamingStrategy;
        private ColumnNamingStrategy columnNamingStrategy;
        private boolean applierDDLEnabled = true;
        private int connectionRetriesMax = 5;
        private int connectionBackoff = 3000;
        private String databaseDialectName = POSTGRES_DIALECT_NAME;

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

        public SinkConfig build() {
            return new SinkConfig(this);
        }
    }
}
