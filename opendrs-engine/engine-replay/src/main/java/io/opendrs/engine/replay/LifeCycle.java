package io.opendrs.engine.replay;

import io.opendrs.engine.replay.context.TaskContext;

/**
 * Common interface for component life cycle methods.
 * Each component must implement this interface
 *
 */
public interface LifeCycle {

    /**
     * Prepare resources
     * this method must be called before calling apply method
     *
     * @param taskContext task context  {@link TaskContext}
     */
    void prepare(TaskContext taskContext);

    /**
     * Release the resources
     * this method must be called when the object is no more used
     *
     */
    void release();
}
