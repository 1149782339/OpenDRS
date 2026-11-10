package io.opendrs.engine.datacheck.incremental;

import io.opendrs.engine.datacheck.InconsistentRowStore;
import java.util.List;

/** Looks up a batch of PKs on both sides, diffs with HashBuckets, and writes the store. */
public interface PkBatchChecker {

    void check(CheckedTablePair tables, List<PkRow> keys, InconsistentRowStore store);
}
