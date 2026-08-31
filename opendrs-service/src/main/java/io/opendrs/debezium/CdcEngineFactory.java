package io.opendrs.debezium;

import io.opendrs.debezium.DebeziumEngineConfig.EngineSpec;

public interface CdcEngineFactory {

    /**
     * One Engine: snapshot schema and data, then stream. {@code onSnapshotCompleted} is invoked
     * when Debezium reports Initial Snapshot {@code COMPLETED} (or streaming has begun). The Engine
     * is not stopped by that signal.
     */
    CdcEngine create(EngineSpec spec, Runnable onSnapshotCompleted);
}
