package io.opendrs.engine.datacheck.utils;

import java.util.ArrayList;
import java.util.List;

public class HashBuckets {
    private final List<HashBucket> buckets;

    public HashBuckets(int bucketNum) {
        if (bucketNum <= 0) {
            throw new IllegalArgumentException("bucketNum must be > 0");
        }
        this.buckets = new ArrayList<>(bucketNum);
        for (int i = 0; i < bucketNum; i++) {
            buckets.add(new HashBucket());
        }
    }

    public void clear() {
        for (HashBucket bucket : buckets) {
            bucket.clear();
        }
    }

    public void add(String primaryKey, long primaryKeyHash, long rowHash) {
        int index = Math.floorMod(primaryKeyHash, buckets.size());
        buckets.get(index).add(primaryKey, primaryKeyHash, rowHash);
    }

    public DiffResult diff(HashBuckets other) {
        if (other == null) {
            throw new IllegalArgumentException("other cannot be null");
        }
        if (buckets.size() != other.buckets.size()) {
            throw new IllegalArgumentException(
                    "bucket count mismatch: " + buckets.size() + " vs " + other.buckets.size());
        }
        DiffResult result = DiffResult.empty();
        for (int i = 0; i < buckets.size(); i++) {
            result = result.merge(HashBucket.diff(buckets.get(i), other.buckets.get(i)));
        }
        return result;
    }
}
