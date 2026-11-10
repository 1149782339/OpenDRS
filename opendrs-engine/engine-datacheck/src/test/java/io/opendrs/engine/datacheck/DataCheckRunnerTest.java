package io.opendrs.engine.datacheck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.datacheck.utils.DiffResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataCheckRunnerTest {

    @Test
    void applyDiffMapsFlagsAndClearsMatchingKeys() {
        InMemoryStore store = new InMemoryStore();
        TableId table = TableId.of("s", "t");
        // prior marks that should be cleared when the row matches again
        store.markInconsistent(table, List.of("match", "changed"), InconsistentRowStore.FLAG_DIFFERENT);

        DiffResult diff = new DiffResult(
                List.of("only-src"),
                List.of("only-tgt"),
                List.of("changed"));
        DataCheckRunner.applyDiff(store, table, diff, List.of("match", "changed", "only-src"));

        assertEquals(InconsistentRowStore.FLAG_SOURCE_ONLY, store.flag(table, "only-src"));
        assertEquals(InconsistentRowStore.FLAG_TARGET_ONLY, store.flag(table, "only-tgt"));
        assertEquals(InconsistentRowStore.FLAG_DIFFERENT, store.flag(table, "changed"));
        assertFalse(store.isInconsistent(table, "match"));
        assertTrue(store.isInconsistent(table, "only-src"));
    }

    @Test
    void applyDiffBothAbsentKeysAreMarkedConsistent() {
        InMemoryStore store = new InMemoryStore();
        TableId table = TableId.of("s", "t");
        store.markInconsistent(table, List.of("gone"), InconsistentRowStore.FLAG_SOURCE_ONLY);

        DataCheckRunner.applyDiff(store, table, DiffResult.empty(), List.of("gone", "still-there"));

        assertFalse(store.isInconsistent(table, "gone"));
        assertFalse(store.isInconsistent(table, "still-there"));
    }

    @Test
    void applyDiffEmptyMeansAllSourceKeysConsistent() {
        InMemoryStore store = new InMemoryStore();
        TableId table = TableId.of("s", "t");
        store.markInconsistent(table, List.of("a"), InconsistentRowStore.FLAG_SOURCE_ONLY);

        DataCheckRunner.applyDiff(store, table, DiffResult.empty(), List.of("a", "b"));

        assertFalse(store.isInconsistent(table, "a"));
        assertFalse(store.isInconsistent(table, "b"));
        assertTrue(store.listInconsistent(table).isEmpty());
    }

    private static final class InMemoryStore implements InconsistentRowStore {
        private final Map<String, Map<String, Integer>> byTable = new HashMap<>();

        private Map<String, Integer> rows(TableId table) {
            return byTable.computeIfAbsent(table.identifier(), k -> new HashMap<>());
        }

        Integer flag(TableId table, String pk) {
            return rows(table).get(pk);
        }

        @Override
        public void markInconsistent(TableId table, List<String> pks, int flag) {
            Map<String, Integer> rows = rows(table);
            for (String pk : pks) {
                rows.put(pk, flag);
            }
        }

        @Override
        public void markConsistent(TableId table, List<String> pks) {
            Map<String, Integer> rows = rows(table);
            for (String pk : pks) {
                rows.remove(pk);
            }
        }

        @Override
        public List<String> listInconsistent(TableId table) {
            return new ArrayList<>(rows(table).keySet());
        }

        @Override
        public boolean isInconsistent(TableId table, String pk) {
            return rows(table).containsKey(pk);
        }
    }
}
