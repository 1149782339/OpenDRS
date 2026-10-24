package io.opendrs.engine.replay.context;

/**
 * Task context for applier lifecycle. Kafka Connect offset maps were stripped; prepare() is a
 * no-op hook so {@link io.opendrs.engine.replay.LifeCycle} stays.
 */
public class TaskContext {

    public static TaskContext empty() {
        return new TaskContext();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        public TaskContext build() {
            return new TaskContext();
        }
    }
}
