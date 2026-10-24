package io.opendrs.engine.replay.event;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.common.metadata.TableId;

/**
 * Schema Change Event equivalent to SchemaChangeEvent.
 * See #io.debezium.schema.SchemaChangeEvent
 *
 */
public class SchemaChangeEvent extends ChangeEvent {
    private String ddl;
    private TableId tableId;

    private String transactionId;

    public String getDDL() {
        return ddl;
    }

    public TableId getTableId() {
        return tableId;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String getTransactionId() {
        return transactionId;
    }

    public static final class Builder {
        private String ddl;
        private TableId tableId;
        private String topic;
        private long offset;
        private Integer partition;

        private DatabaseType databaseType;

        private String transactionId;

        private String gtid;

        private Builder() {
        }


        public Builder ddl(String ddl) {
            this.ddl = ddl;
            return this;
        }

        public Builder tableId(TableId tableId) {
            this.tableId = tableId;
            return this;
        }

        public Builder topic(String topic) {
            this.topic = topic;
            return this;
        }

        public Builder offset(long offset) {
            this.offset = offset;
            return this;
        }

        public Builder partition(Integer partition) {
            this.partition = partition;
            return this;
        }

        public Builder transactionId(String transactionId) {
            this.transactionId = transactionId;
            return this;
        }

        public Builder gtid(String gtid) {
            this.gtid = gtid;
            return this;
        }

        public Builder databaseType(DatabaseType databaseType) {
            this.databaseType = databaseType;
            return this;
        }

        public SchemaChangeEvent build() {
            SchemaChangeEvent schemaChangeEvent = new SchemaChangeEvent();
            schemaChangeEvent.tableId = this.tableId;
            schemaChangeEvent.partition = this.partition;
            schemaChangeEvent.ddl = this.ddl;
            schemaChangeEvent.offset = this.offset;
            schemaChangeEvent.topic = this.topic;
            schemaChangeEvent.databaseType = this.databaseType;
            schemaChangeEvent.transactionId = this.transactionId;
            schemaChangeEvent.gtid = this.gtid;
            return schemaChangeEvent;
        }
    }
}
