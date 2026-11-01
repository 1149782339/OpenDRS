package io.opendrs.engine.schema.meta;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record DatabaseObject(ObjectId id, String sourceDdlText, List<ObjectId> dependencies) {

    /**
     * Defensive copy. The two-argument constructor passes {@code List.of()}, which is immutable,
     * and {@link #addDependencies} appends in place after the object has been collected. Without
     * this copy that append throws {@link UnsupportedOperationException}.
     *
     * <p>Dependencies are therefore mutable: do not use an instance as a hash key before they are
     * filled in, because the record's generated {@code hashCode} covers them.
     */
    public DatabaseObject {
        Objects.requireNonNull(id, "id");
        dependencies = dependencies == null ? new ArrayList<>() : new ArrayList<>(dependencies);
    }

    public DatabaseObject(ObjectId id, String sourceDdlText) {
        this(id, sourceDdlText, List.of());
    }

    public void addDependencies(List<ObjectId> dependencies) {
        this.dependencies.addAll(dependencies);
    }
}

