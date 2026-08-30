package io.opendrs.debezium;

import io.opendrs.debezium.DebeziumEngineConfig.EngineSpec;

public interface CdcEngineFactory {

    CdcEngine createSchemaSnapshot(EngineSpec spec);

    CdcEngine createIncremental(EngineSpec spec);
}
