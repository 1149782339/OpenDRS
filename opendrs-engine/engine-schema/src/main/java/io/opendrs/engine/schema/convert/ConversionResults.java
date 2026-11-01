package io.opendrs.engine.schema.convert;

import io.opendrs.engine.schema.meta.DatabaseObject;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Conversion results for a whole export batch: one {@link ConversionResult} per source object,
 * keyed by that object.
 *
 * <p>Iteration order is significant: entries are kept in source dependency order (topological),
 * so the importer can execute them in encounter order. Backed by an insertion-ordered map.
 * Entries with status {@code DROPPED} are kept, so dropped source objects stay visible in the
 * report and can be taken over manually.
 *
 * <p>Keys are {@link DatabaseObject}s whose record hash covers their mutable dependency list:
 * only insert objects whose dependencies are final (export attaches them before convert runs),
 * and never mutate an object after inserting it.
 */
public class ConversionResults {

    private final Map<DatabaseObject, ConversionResult> results = new LinkedHashMap<>();

    public ConversionResult get(DatabaseObject source) {
        return results.get(source);
    }

    public void put(DatabaseObject source, ConversionResult result) {
        results.put(
                Objects.requireNonNull(source, "source"),
                Objects.requireNonNull(result, "result"));
    }

    public int size() {
        return results.size();
    }

    public boolean isEmpty() {
        return results.isEmpty();
    }

    /** Entries in import (dependency) order. */
    public Iterable<Map.Entry<DatabaseObject, ConversionResult>> entries() {
        return Collections.unmodifiableSet(results.entrySet());
    }
}
