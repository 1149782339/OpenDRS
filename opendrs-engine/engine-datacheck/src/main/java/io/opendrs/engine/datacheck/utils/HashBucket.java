package io.opendrs.engine.datacheck.utils;

import java.util.*;

public class HashBucket {

    private long hashCode;
    private final Map<String, Long> primaryKeyHashMap;  // pk -> 该行的 hash

    public HashBucket() {
        hashCode = 0;
        primaryKeyHashMap = new HashMap<>();
    }

    public void add(String pk, long rowHash) {
        Long old = primaryKeyHashMap.put(pk, rowHash);
        if (old != null) {
            hashCode ^= old;          // 先去掉旧值
        }
        hashCode ^= rowHash;          // 再加入新值
    }

    public void clear() {
        hashCode = 0;
        primaryKeyHashMap.clear();
    }

    /**
     * 一次计算，返回三种差异。
     */
    public static DiffResult diff(HashBucket b1, HashBucket b2) {
        if (b1 == null || b2 == null) {
            throw new IllegalArgumentException("bucket cannot be null");
        }

        // 快速路径
        if (b1.hashCode == b2.hashCode
                && b1.primaryKeyHashMap.size() == b2.primaryKeyHashMap.size()) {
            return DiffResult.empty();
        }

        Set<String> keys1 = b1.primaryKeyHashMap.keySet();
        Set<String> keys2 = b2.primaryKeyHashMap.keySet();

        // left: 只在 b1
        Set<String> left = new HashSet<>(keys1);
        left.removeAll(keys2);

        // right: 只在 b2
        Set<String> right = new HashSet<>(keys2);
        right.removeAll(keys1);

        // both: 两边都有，但 hash 不同
        Set<String> intersection = new HashSet<>(keys1);
        intersection.retainAll(keys2);
        List<String> both = new ArrayList<>();
        for (String pk : intersection) {
            if (!b1.primaryKeyHashMap.get(pk).equals(b2.primaryKeyHashMap.get(pk))) {
                both.add(pk);
            }
        }

        return new DiffResult(new ArrayList<>(left), new ArrayList<>(right), both);
    }
}