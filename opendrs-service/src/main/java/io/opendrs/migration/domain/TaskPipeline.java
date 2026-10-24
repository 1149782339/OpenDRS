package io.opendrs.migration.domain;

import java.time.Instant;
import java.util.Map;

/**
 * Row in {@code task_pipelines}: one physical source -> target link of a task, with its own
 * Debezium engine, offset rows and schema history.
 *
 * <p>A task is what the user sees (what to move, with what options); a pipeline is what runs.
 * One-way tasks own one pipeline row, bidirectional tasks own two (endpoints swapped). The row
 * is self-describing — source/target endpoints plus direction-specific options — so the runtime
 * never needs a {@code direction} discriminator.
 *
 * <p>{@link #captureOptions} / {@link #replayOptions} hold what the runtime cannot derive:
 * today only the synthetic full-load GTID namespace each direction stamps on its target
 * (bidirectional loop prevention). The drop set is derived at runtime from the sibling
 * pipeline's namespace plus the target endpoint's {@code server_uuid}.
 */
public class TaskPipeline {

    private Long id;
    private Long taskId;
    private Long sourceEndpointId;
    private Long targetEndpointId;
    private Map<String, Object> captureOptions;
    private Map<String, Object> replayOptions;
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

    public Long getSourceEndpointId() {
        return sourceEndpointId;
    }

    public void setSourceEndpointId(Long sourceEndpointId) {
        this.sourceEndpointId = sourceEndpointId;
    }

    public Long getTargetEndpointId() {
        return targetEndpointId;
    }

    public void setTargetEndpointId(Long targetEndpointId) {
        this.targetEndpointId = targetEndpointId;
    }

    public Map<String, Object> getCaptureOptions() {
        return captureOptions;
    }

    public void setCaptureOptions(Map<String, Object> captureOptions) {
        this.captureOptions = captureOptions;
    }

    public Map<String, Object> getReplayOptions() {
        return replayOptions;
    }

    public void setReplayOptions(Map<String, Object> replayOptions) {
        this.replayOptions = replayOptions;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
