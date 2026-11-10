package io.opendrs.engine.datacheck.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HashBucketsTest {

    private static void add(HashBuckets buckets, String pk, long rowHash) {
        buckets.add(pk, HashUtil.getHash(pk), rowHash);
    }

    @Test
    void identicalBucketsYieldEmptyDiff() {
        HashBuckets left = new HashBuckets(8);
        HashBuckets right = new HashBuckets(8);
        add(left, "a", 11L);
        add(left, "b", 22L);
        add(right, "a", 11L);
        add(right, "b", 22L);

        DiffResult diff = left.diff(right);
        assertTrue(diff.isEmpty());
        assertTrue(diff.left().isEmpty());
        assertTrue(diff.right().isEmpty());
        assertTrue(diff.both().isEmpty());
    }

    @Test
    void missingOnEachSideReported() {
        HashBuckets left = new HashBuckets(4);
        HashBuckets right = new HashBuckets(4);
        add(left, "only-left", 1L);
        add(left, "shared", 2L);
        add(right, "shared", 2L);
        add(right, "only-right", 3L);

        DiffResult diff = left.diff(right);
        assertFalse(diff.isEmpty());
        assertEquals(1, diff.left().size());
        assertTrue(diff.left().contains("only-left"));
        assertEquals(1, diff.right().size());
        assertTrue(diff.right().contains("only-right"));
        assertTrue(diff.both().isEmpty());
    }

    @Test
    void changedValueGoesToBoth() {
        HashBuckets left = new HashBuckets(4);
        HashBuckets right = new HashBuckets(4);
        add(left, "pk1", 100L);
        add(right, "pk1", 200L);

        DiffResult diff = left.diff(right);
        assertEquals(1, diff.both().size());
        assertTrue(diff.both().contains("pk1"));
        assertTrue(diff.left().isEmpty());
        assertTrue(diff.right().isEmpty());
    }

    @Test
    void swappedValuesAreDetected() {
        // 源 {a:X, b:Y} vs 目标 {a:Y, b:X}：异或行 hash 相同，混入 pk 后应检出
        HashBuckets left = new HashBuckets(1); // 强制进同一桶
        HashBuckets right = new HashBuckets(1);
        add(left, "a", 10L);
        add(left, "b", 20L);
        add(right, "a", 20L);
        add(right, "b", 10L);

        DiffResult diff = left.diff(right);
        assertFalse(diff.isEmpty());
        assertEquals(2, diff.both().size());
        assertTrue(diff.both().contains("a"));
        assertTrue(diff.both().contains("b"));
    }

    @Test
    void overwriteSamePkUpdatesCorrectly() {
        HashBuckets left = new HashBuckets(4);
        HashBuckets right = new HashBuckets(4);
        add(left, "pk", 1L);
        add(left, "pk", 99L); // 覆盖
        add(right, "pk", 99L);

        DiffResult diff = left.diff(right);
        assertTrue(diff.isEmpty());

        HashBuckets stale = new HashBuckets(4);
        add(stale, "pk", 1L);
        DiffResult staleDiff = left.diff(stale);
        assertTrue(staleDiff.both().contains("pk"));
    }

    @Test
    void negativePrimaryKeyHashIndexesSafely() {
        HashBuckets left = new HashBuckets(7);
        HashBuckets right = new HashBuckets(7);
        long negativeHash = Long.MIN_VALUE;
        left.add("neg", negativeHash, 42L);
        right.add("neg", negativeHash, 42L);

        DiffResult diff = left.diff(right);
        assertTrue(diff.isEmpty());
    }

    @Test
    void mismatchedBucketCountsThrow() {
        HashBuckets left = new HashBuckets(2);
        HashBuckets right = new HashBuckets(3);
        assertThrows(IllegalArgumentException.class, () -> left.diff(right));
    }

    @Test
    void invalidBucketNumThrows() {
        assertThrows(IllegalArgumentException.class, () -> new HashBuckets(0));
        assertThrows(IllegalArgumentException.class, () -> new HashBuckets(-1));
    }

    @Test
    void nullOtherThrows() {
        HashBuckets left = new HashBuckets(2);
        assertThrows(IllegalArgumentException.class, () -> left.diff(null));
    }
}
