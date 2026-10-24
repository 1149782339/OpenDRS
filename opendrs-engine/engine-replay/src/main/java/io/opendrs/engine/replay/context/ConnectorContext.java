package io.opendrs.engine.replay.context;


/**
 * Connector Context
 *
 */
public interface ConnectorContext {
    /**
     * Judge if connector task is running
     *
     * @return if connector task is running
     */
    boolean isRunning();
}
