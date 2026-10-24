package io.opendrs.engine.replay.util;

/**
 * Hash util, used by write set {@link WriteSet}
 *
 * The hash is only ever kept in memory (the write set history), never persisted or exchanged, so the algorithm may change freely — only
 * distribution quality matters, and FNV-1a is sufficient for conflict bucketing.
 *
 */
public class HashUtil {

    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    public long getHash(String value) {
        long hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
