package io.opendrs.migration.domain;

import java.time.Instant;

/**
 * Row in {@code debezium_schema_history} for {@code TaskSchemaHistory}.
 */
public class DebeziumSchemaHistory {

    private Long id;
    private Long taskId;
    private Integer recordSeq;
    private String historyData;
    private Instant createdAt;

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

    public Integer getRecordSeq() {
        return recordSeq;
    }

    public void setRecordSeq(Integer recordSeq) {
        this.recordSeq = recordSeq;
    }

    public String getHistoryData() {
        return historyData;
    }

    public void setHistoryData(String historyData) {
        this.historyData = historyData;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
