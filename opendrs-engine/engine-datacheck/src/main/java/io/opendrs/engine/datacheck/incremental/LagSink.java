package io.opendrs.engine.datacheck.incremental;

import io.opendrs.engine.common.metadata.TableId;

@FunctionalInterface
public interface LagSink {

    void update(TableId sourceTable, long lagMs);
}
