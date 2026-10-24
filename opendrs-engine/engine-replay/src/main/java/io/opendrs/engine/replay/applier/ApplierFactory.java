package io.opendrs.engine.replay.applier;

import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.context.ConnectorContext;
import io.opendrs.engine.replay.event.ChangeEvent;
import java.util.Collection;

/**
 * Creates the per-worker JDBC executor. OpenDRS supports JDBC targets only, so the factory is
 * a one-liner kept as the seam tests replace when faking the executor.
 */
public class ApplierFactory {

    public static Applier<Collection<ChangeEvent>> createJdbcApplier(
            ConnectorContext context, ReplayConfig config) {
        return new JdbcApplier(context, config);
    }
}
