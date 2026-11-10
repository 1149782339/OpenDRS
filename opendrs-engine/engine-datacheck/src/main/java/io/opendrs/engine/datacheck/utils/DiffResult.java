package io.opendrs.engine.datacheck.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * 分桶比对结果。
 * left: 只在源端；right: 只在目标端；both: 两边都有但行 hash 不同。
 */
public record DiffResult(List<String> left, List<String> right, List<String> both) {

    public DiffResult {
        left = List.copyOf(left);
        right = List.copyOf(right);
        both = List.copyOf(both);
    }

    public static DiffResult empty() {
        return new DiffResult(List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return left.isEmpty() && right.isEmpty() && both.isEmpty();
    }

    public DiffResult merge(DiffResult other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        List<String> mergedLeft = new ArrayList<>(left.size() + other.left.size());
        mergedLeft.addAll(left);
        mergedLeft.addAll(other.left);
        List<String> mergedRight = new ArrayList<>(right.size() + other.right.size());
        mergedRight.addAll(right);
        mergedRight.addAll(other.right);
        List<String> mergedBoth = new ArrayList<>(both.size() + other.both.size());
        mergedBoth.addAll(both);
        mergedBoth.addAll(other.both);
        return new DiffResult(mergedLeft, mergedRight, mergedBoth);
    }
}
