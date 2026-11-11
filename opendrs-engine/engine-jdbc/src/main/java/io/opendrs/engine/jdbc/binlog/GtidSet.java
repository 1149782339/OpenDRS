package io.opendrs.engine.jdbc.binlog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A MySQL GTID set ({@code uuid:1-5:7,uuid2:1-3}), parsed into per-source-UUID interval lists so
 * two sets can be compared by containment.
 *
 * <p>Both spellings the engine meets are accepted: the server's {@code Executed_Gtid_Set} (with
 * newlines after the commas) and Debezium's offset {@code gtids} value. Tagged GTIDs of MySQL
 * 8.3+ ({@code uuid:tag:1-5}) keep the tag as part of the key, so tags never mix.
 */
public final class GtidSet {

    private final Map<String, List<long[]>> intervals;

    private GtidSet(Map<String, List<long[]>> intervals) {
        this.intervals = intervals;
    }

    public static GtidSet parse(String text) {
        Map<String, List<long[]>> parsed = new LinkedHashMap<>();
        if (text == null) {
            return new GtidSet(parsed);
        }
        String normalized = text.replace("\n", "").replace("\r", "").trim();
        if (normalized.isEmpty()) {
            return new GtidSet(parsed);
        }
        for (String member : normalized.split(",")) {
            String trimmed = member.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split(":");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Malformed GTID set member: " + trimmed);
            }
            String key = parts[0].toLowerCase(Locale.ROOT);
            List<long[]> list = parsed.computeIfAbsent(key, k -> new ArrayList<>());
            String tagKey = key;
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i].trim();
                if (part.isEmpty()) {
                    continue;
                }
                if (!Character.isDigit(part.charAt(0))) {
                    // MySQL 8.3+ tag: the following intervals belong to uuid:tag.
                    tagKey = key + ":" + part.toLowerCase(Locale.ROOT);
                    list = parsed.computeIfAbsent(tagKey, k -> new ArrayList<>());
                    continue;
                }
                int dash = part.indexOf('-');
                long start = Long.parseLong(dash < 0 ? part : part.substring(0, dash));
                long end = dash < 0 ? start : Long.parseLong(part.substring(dash + 1));
                list.add(new long[]{start, end});
            }
        }
        parsed.values().forEach(GtidSet::normalize);
        parsed.values().removeIf(List::isEmpty);
        return new GtidSet(parsed);
    }

    private static void normalize(List<long[]> list) {
        list.sort((a, b) -> Long.compare(a[0], b[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] interval : list) {
            if (!merged.isEmpty() && interval[0] <= merged.getLast()[1] + 1) {
                long[] last = merged.getLast();
                last[1] = Math.max(last[1], interval[1]);
            } else {
                merged.add(new long[]{interval[0], interval[1]});
            }
        }
        list.clear();
        list.addAll(merged);
    }

    public boolean isEmpty() {
        return intervals.isEmpty();
    }

    /** True when every transaction of {@code other} is also in this set. */
    public boolean contains(GtidSet other) {
        for (Map.Entry<String, List<long[]>> entry : other.intervals.entrySet()) {
            List<long[]> mine = intervals.getOrDefault(entry.getKey(), Collections.emptyList());
            for (long[] needed : entry.getValue()) {
                if (!covered(mine, needed)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean covered(List<long[]> mine, long[] needed) {
        for (long[] interval : mine) {
            if (interval[0] <= needed[0] && interval[1] >= needed[1]) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, List<long[]>> entry : intervals.entrySet()) {
            if (!text.isEmpty()) {
                text.append(',');
            }
            text.append(entry.getKey());
            for (long[] interval : entry.getValue()) {
                text.append(':').append(interval[0]);
                if (interval[1] != interval[0]) {
                    text.append('-').append(interval[1]);
                }
            }
        }
        return text.toString();
    }
}
