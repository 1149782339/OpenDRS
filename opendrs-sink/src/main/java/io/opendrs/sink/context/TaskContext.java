/*
 *  Copyright DbSink Authors.
 *  This source code is licensed under the Apache License Version 2.0, available
 *  at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.opendrs.sink.context;

/**
 * Task context for applier lifecycle. Kafka Connect offset maps were stripped; prepare() is a
 * no-op hook so {@link io.opendrs.sink.LifeCycle} stays.
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
