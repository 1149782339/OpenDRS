package io.opendrs.engine.replay.event;

import io.opendrs.common.metadata.DatabaseType;

/**
 * Change Event equivalent to ChangeEvent.
 * See #io.debezium.engine.ChangeEvent
 *
 */
public abstract class ChangeEvent {
    protected String topic;
    protected long offset;
    protected Integer partition;

    protected DatabaseType databaseType;

    /**
     * Source GTID ('uuid:seq') this event belongs to, null for snapshot rows and for any source
     * that does not log GTIDs. Only used by the bidirectional loop-prevention path.
     */
    protected String gtid;

    public String getTopic() {
        return topic;
    }

    public String getGtid() {
        return gtid;
    }

    public long getOffset() {
        return offset;
    }

    public Integer getPartition() {
        return partition;
    }

    public abstract String getTransactionId();

    public DatabaseType getDatabaseType() {
        return databaseType;
    }

}
