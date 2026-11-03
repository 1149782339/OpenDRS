package io.opendrs.engine.schema.convert;

import io.opendrs.engine.schema.meta.DatabaseObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * Snapshot of the entries in import (dependency) order.
     *
     * <p>Immutable copies, not a live view: a caller cannot see later {@link #put}s through a list
     * it already holds, and cannot write through an entry ({@code Map.Entry.setValue} throws).
     */
    public List<Map.Entry<DatabaseObject, ConversionResult>> entries() {
        List<Map.Entry<DatabaseObject, ConversionResult>> snapshot = new ArrayList<>(results.size());
        for (Map.Entry<DatabaseObject, ConversionResult> entry : results.entrySet()) {
            snapshot.add(Map.entry(entry.getKey(), entry.getValue()));
        }
        return Collections.unmodifiableList(snapshot);
    }
}
