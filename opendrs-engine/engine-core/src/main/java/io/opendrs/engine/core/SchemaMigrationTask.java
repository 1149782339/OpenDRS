package io.opendrs.engine.core;

import io.opendrs.common.metadata.TableId;
import java.util.List;

/**
 * Handle for one schema migration run. {@link #created()} and {@link #skipped()} are empty while
 * the run is in flight and populated once it reaches a terminal state.
 */
public interface SchemaMigrationTask extends MigrationTask {

    /** Target tables created by this run. */
    List<TableId> created();

    /** Tables that already existed on the target and were left untouched. */
    List<TableId> skipped();
}
