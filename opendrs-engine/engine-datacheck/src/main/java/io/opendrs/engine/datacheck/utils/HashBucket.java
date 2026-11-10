package io.opendrs.engine.datacheck.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HashBucket {

    private long bucketHash;
    private final Map<String, Long> primaryKeyHashMap; // pk -> 该行的 hash

    public HashBucket() {
        bucketHash = 0;
        primaryKeyHashMap = new HashMap<>();
    }

    /**
     * 混入主键 hash，避免仅异或 rowHash 时交换值被误判为一致。
     */
    static long mix(long pkHash, long rowHash) {
        long x = pkHash * 0x9E3779B97F4A7C15L;
        x ^= rowHash;
        // murmur3 fmix64
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= (x >>> 33);
        return x;
    }

    public void add(String pk, long pkHash, long rowHash) {
        Long old = primaryKeyHashMap.put(pk, rowHash);
        if (old != null) {
            bucketHash ^= mix(pkHash, old); // 先去掉旧值
        }
        bucketHash ^= mix(pkHash, rowHash); // 再加入新值
    }

    public void clear() {
        bucketHash = 0;
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
        if (b1.bucketHash == b2.bucketHash
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
