package io.opendrs.engine.datacheck.type.serialize;

import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.common.metadata.TableDefinition;

public class SerializeConfiguration {
    TableDefinition table;
    boolean includeFloat;
    DatabaseType sourceDatabase;
}
