package io.opendrs.engine.datacheck.utils;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class HashBuckets {
    private final List<HashBucket> buckets;

    public HashBuckets(int bucketNum) {
        this.buckets = new ArrayList<>(bucketNum);
    }

    public void clear() {
        for (HashBucket bucket : buckets) {
            bucket.clear();
        }
    }

    public void add(String primaryKey, long primaryKeyHash, long rowHash) {
        int index = (int) (primaryKeyHash % buckets.size());
        buckets.get(index).add(primaryKey, rowHash);
    }

    public DiffResult diff() {
        for(int i = 0; i )
    }
}
