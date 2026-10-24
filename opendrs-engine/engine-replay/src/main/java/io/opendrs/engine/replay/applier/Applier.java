package io.opendrs.engine.replay.applier;

import io.opendrs.engine.replay.LifeCycle;
import io.opendrs.engine.replay.exception.ApplierException;

/**
 * Applier interface defines the lifecycle and the function of an applier
 *
 */
public interface Applier<T> extends LifeCycle {
    /**
     * Apply the event
     * this method must be called after calling prepare method
     *
     * @param applierEvent applier event
     * @throws ApplierException if there is an error applying
     */
    void apply(T applierEvent) throws ApplierException;
}
