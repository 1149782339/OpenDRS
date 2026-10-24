package io.opendrs.migration.domain;

import java.time.Instant;

/**
 * Row in {@code debezium_offsets} for {@code TaskOffsetBackingStore}.
 */
public class DebeziumOffset {

    private Long id;
    private Long pipelineId;
    private String offsetKey;
    private String offsetVal;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(Long pipelineId) {
        this.pipelineId = pipelineId;
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
