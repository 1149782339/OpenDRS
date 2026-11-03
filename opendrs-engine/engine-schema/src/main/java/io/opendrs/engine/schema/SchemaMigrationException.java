package io.opendrs.engine.schema;

/**
 * Schema migration failed — a target table could not be created, or the source DDL could not be
 * converted.
 *
 * <p>Checked, so callers cannot drop the failure on the floor. The engine facade translates it into
 * the replay module's exception type at the boundary, keeping this module unaware of it.
 */
public class SchemaMigrationException extends Exception {

    public SchemaMigrationException(String message) {
        super(message);
    }

    public SchemaMigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
