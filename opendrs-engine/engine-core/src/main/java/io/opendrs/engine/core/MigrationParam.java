package io.opendrs.engine.core;

import io.opendrs.engine.replay.mapping.TableSelection;
import io.opendrs.jdbc.JdbcConnectionConfig;

/**
 * Common shape of every engine request.
 *
 * <p>{@link #selection()} is optional: {@code null} means "no filter", which is the whole source —
 * every schema the connection can read, including ones created later. The same holds for a
 * selection whose include lists are all empty.
 */
public sealed interface MigrationParam
        permits SchemaMigrationParam, DataMigrationParam, PrecheckParam {

    JdbcConnectionConfig source();

    JdbcConnectionConfig target();

    TableSelection selection();
}
