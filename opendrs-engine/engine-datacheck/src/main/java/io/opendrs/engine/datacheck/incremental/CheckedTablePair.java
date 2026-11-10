package io.opendrs.engine.datacheck.incremental;

import io.opendrs.engine.common.metadata.TableId;
import java.util.Objects;

public record CheckedTablePair(TableId source, TableId target) {

    public CheckedTablePair {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
    }
}
