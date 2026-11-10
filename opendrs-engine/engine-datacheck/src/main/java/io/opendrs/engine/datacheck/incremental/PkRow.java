package io.opendrs.engine.datacheck.incremental;

import java.util.List;
import java.util.Objects;

/** One unique primary key in an incremental batch. */
public record PkRow(String encoded, List<Object> bindValues) {

    public PkRow {
        Objects.requireNonNull(encoded, "encoded");
        bindValues = bindValues == null ? List.of() : List.copyOf(bindValues);
    }
}
