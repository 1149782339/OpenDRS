package io.opendrs.common.log;

/**
 * Severity of a {@link MigrationLogListener} message.
 *
 * <p>Deliberately small: the engine has nothing to say at a debug level that an operator would want
 * to read, and a finer scale would only force the service to invent a mapping.
 */
public enum MigrationLogLevel {

    /** Normal progress: a table was created, a snapshot finished, a phase began. */
    INFO,

    /** Something was skipped, retried or degraded, but the migration is still on track. */
    WARNING,

    /** The run is failing or lost data it was supposed to carry. */
    ERROR
}
