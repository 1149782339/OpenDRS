package io.opendrs.engine.schema.imports;

import io.opendrs.engine.schema.convert.ConversionResult;
import io.opendrs.engine.schema.meta.ObjectId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Import results for a whole conversion batch: one {@link ConversionResult} per source object,
 * keyed by that object's identity, recording how the import of that object went
 * ({@code SUCCEEDED} executed, {@code FAILED} execution error, {@code DROPPED} nothing to
 * execute).
 *
 * <p>Iteration order matches execution order (the source dependency order of
 * {@code ConversionResults}).
 */
public class ImportResults {

    private final Map<ObjectId, ConversionResult> results = new LinkedHashMap<>();

    public ConversionResult get(ObjectId sourceId) {
        return results.get(sourceId);
    }

    public void put(ObjectId sourceId, ConversionResult result) {
        results.put(
                Objects.requireNonNull(sourceId, "sourceId"),
                Objects.requireNonNull(result, "result"));
    }

    public int size() {
        return results.size();
    }

    public boolean isEmpty() {
        return results.isEmpty();
    }

    /**
     * Snapshot of the entries in execution order.
     *
     * <p>Returns immutable entries copied out of the backing map, not a live view of it: a caller
     * holding this list cannot see later {@link #put}s, and cannot write through an entry into the
     * results ({@code Map.Entry.setValue} throws).
     */
    public List<Map.Entry<ObjectId, ConversionResult>> entries() {
        List<Map.Entry<ObjectId, ConversionResult>> snapshot = new ArrayList<>(results.size());
        for (Map.Entry<ObjectId, ConversionResult> entry : results.entrySet()) {
            snapshot.add(Map.entry(entry.getKey(), entry.getValue()));
        }
        return Collections.unmodifiableList(snapshot);
    }
}
