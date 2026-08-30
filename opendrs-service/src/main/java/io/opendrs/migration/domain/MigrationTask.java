package io.opendrs.migration.domain;

import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.api.request.TableSelection;
import java.time.Instant;

public class MigrationTask {

    private Long id;
    private String name;
    private MigrationMode mode;
    private JobPhase jobPhase;
    private JobState jobState;
    private Long sourceConnectionId;
    private Long targetConnectionId;
    private TableSelection tablesJson;
    private MigrationOptions optionsJson;
    private int tablesTotal;
    private int tablesDone;
    private long rowsDone;
    private Long lagMs;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public MigrationMode getMode() {
        return mode;
    }

    public void setMode(MigrationMode mode) {
        this.mode = mode;
    }

    public JobPhase getJobPhase() {
        return jobPhase;
    }

    public void setJobPhase(JobPhase jobPhase) {
        this.jobPhase = jobPhase;
    }

    public JobState getJobState() {
        return jobState;
    }

    public void setJobState(JobState jobState) {
        this.jobState = jobState;
    }

    public Long getSourceConnectionId() {
        return sourceConnectionId;
    }

    public void setSourceConnectionId(Long sourceConnectionId) {
        this.sourceConnectionId = sourceConnectionId;
    }

    public Long getTargetConnectionId() {
        return targetConnectionId;
    }

    public void setTargetConnectionId(Long targetConnectionId) {
        this.targetConnectionId = targetConnectionId;
    }

    public TableSelection getTablesJson() {
        return tablesJson;
    }

    public void setTablesJson(TableSelection tablesJson) {
        this.tablesJson = tablesJson;
    }

    public MigrationOptions getOptionsJson() {
        return optionsJson;
    }

    public void setOptionsJson(MigrationOptions optionsJson) {
        this.optionsJson = optionsJson;
    }

    public int getTablesTotal() {
        return tablesTotal;
    }

    public void setTablesTotal(int tablesTotal) {
        this.tablesTotal = tablesTotal;
    }

    public int getTablesDone() {
        return tablesDone;
    }

    public void setTablesDone(int tablesDone) {
        this.tablesDone = tablesDone;
    }

    public long getRowsDone() {
        return rowsDone;
    }

    public void setRowsDone(long rowsDone) {
        this.rowsDone = rowsDone;
    }

    public Long getLagMs() {
        return lagMs;
    }

    public void setLagMs(Long lagMs) {
        this.lagMs = lagMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isJobInFlight() {
        return jobState == JobState.STARTING
                || jobState == JobState.RUNNING
                || jobState == JobState.STOPPING;
    }
}
