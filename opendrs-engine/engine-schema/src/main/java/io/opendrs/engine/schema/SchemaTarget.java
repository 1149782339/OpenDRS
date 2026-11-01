package io.opendrs.engine.schema;

import io.opendrs.common.naming.ColumnNamingStrategy;
import io.opendrs.common.naming.TableNamingStrategy;
import io.opendrs.engine.schema.dialect.SchemaDialect;

/**
 * Target-side context for one structure migration run: how source names map to target names, and
 * which dialect applies the DDL.
 *
 * <p>Both naming strategies are the {@code opendrs-common} interfaces, so the caller owns the
 * mapping rules — this module never learns about selection or mapping validation.
 */
public record SchemaTarget(
        TableNamingStrategy tableNaming, ColumnNamingStrategy columnNaming, SchemaDialect dialect) {
}
