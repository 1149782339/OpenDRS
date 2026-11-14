package io.opendrs.migration.service.deferred;

import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.jdbc.deferred.TargetObjectCatalog;
import io.opendrs.migration.api.request.MigrationOptions;
import io.opendrs.migration.domain.MigrationMode;

/**
 * Which tasks load into PK-only target tables (secondary objects dropped before the full load,
 * rebuilt after it, FK enforcement off on the target sessions).
 *
 * <p>One-way full + incremental from MySQL into PostgreSQL or MySQL. Not bidirectional sync (both
 * sides are live, and the tables belong to the user, not to the task), not full-only (TODO: the
 * same flow without the incremental half), not incremental-only (there is no bulk load to speed
 * up).
 */
public final class DeferredObjects {

    private DeferredObjects() {
    }

    public static boolean eligible(
            MigrationMode mode, MigrationOptions options, DatabaseType source, DatabaseType target) {
        boolean bidirectional = options != null && options.topology() == MigrationOptions.Topology.BIDIRECTIONAL;
        return mode == MigrationMode.FULL_AND_INCREMENTAL
                && !bidirectional
                && source == DatabaseType.MYSQL
                && target != null
                && TargetObjectCatalog.supports(target);
    }

    public static MigrationOptions.TargetTablePolicy policyOf(MigrationOptions options) {
        return options == null || options.full() == null
                ? MigrationOptions.TargetTablePolicy.FAIL_IF_NOT_EMPTY
                : options.full().effectiveTargetTablePolicy();
    }
}
