package io.opendrs.migration.domain;

import java.time.Instant;

/**
 * 预留给后续 Debezium OffsetBackingStore。v1 不写入。
 */
public class DebeziumOffset {

    private Long id;
    private Long taskId;
    private String offsetKey;
    private String offsetVal;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public String getOffsetKey() {
        return offsetKey;
    }

    public void setOffsetKey(String offsetKey) {
        this.offsetKey = offsetKey;
    }

    public String getOffsetVal() {
        return offsetVal;
    }

    public void setOffsetVal(String offsetVal) {
        this.offsetVal = offsetVal;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
