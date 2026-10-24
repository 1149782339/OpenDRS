package io.opendrs.migration.domain;

import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.engine.core.TaskState;
import io.opendrs.engine.precheck.PrecheckResults;
import java.time.Instant;

public class MigrationTask {

    private Long id;
    private String publicId;
    private String name;
    private MigrationMode mode;
    private MigrationPhase phase;
    private TaskState state;
    private Long sourceConnectionId;
    private Long targetConnectionId;
    private MigrationOptions optionsJson;
    private PrecheckResults precheckResultsJson;
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

    /** Business-facing UUID (column {@code public_id}); internal joins and FKs keep using {@link #id}. */
    public String getPublicId() {
        return publicId;
    }

    public void setPublicId(String publicId) {
        this.publicId = publicId;
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

    public MigrationPhase getPhase() {
        return phase;
    }

    public void setPhase(MigrationPhase phase) {
        this.phase = phase;
    }

    public TaskState getState() {
        return state;
    }

    public void setState(TaskState state) {
        this.state = state;
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

    public MigrationOptions getOptionsJson() {
        return optionsJson;
    }

    public void setOptionsJson(MigrationOptions optionsJson) {
        this.optionsJson = optionsJson;
    }

    public PrecheckResults getPrecheckResultsJson() {
        return precheckResultsJson;
    }

    public void setPrecheckResultsJson(PrecheckResults precheckResultsJson) {
        this.precheckResultsJson = precheckResultsJson;
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
        return state == TaskState.STARTING
                || state == TaskState.RUNNING
                || state == TaskState.STOPPING;
    }
}
