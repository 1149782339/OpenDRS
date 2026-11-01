package io.opendrs.engine.schema.imports;

import io.opendrs.engine.schema.convert.ConversionResult;
import io.opendrs.engine.schema.meta.ObjectId;
import java.util.Collections;
import java.util.LinkedHashMap;
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

    /** Entries in execution order. */
    public Iterable<Map.Entry<ObjectId, ConversionResult>> entries() {
        return Collections.unmodifiableSet(results.entrySet());
    }
}
