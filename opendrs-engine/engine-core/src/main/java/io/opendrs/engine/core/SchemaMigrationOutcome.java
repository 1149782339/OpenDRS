package io.opendrs.engine.core;

import io.opendrs.jdbc.metadata.Table;
import java.util.List;

/**
 * What one schema-migration run did: target tables it created, and tables that already existed
 * on the target and were therefore left untouched.
 *
 * @param created target coordinates of the objects this run executed successfully
 * @param skipped target coordinates of the objects skipped because they already existed
 */
record SchemaMigrationOutcome(List<Table> created, List<Table> skipped) {
}
