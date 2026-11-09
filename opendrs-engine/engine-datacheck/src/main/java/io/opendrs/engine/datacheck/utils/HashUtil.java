package io.opendrs.engine.datacheck.utils;

import net.jpountz.xxhash.XXHashFactory;

import java.nio.charset.StandardCharsets;

public class HashUtil {
    private static final XXHashFactory FACTORY = XXHashFactory.fastestInstance();

    public static long getHash(String pk) {
        if (pk == null) return 0L;
        byte[] bytes = pk.getBytes(StandardCharsets.UTF_8);
        return FACTORY.hash64().hash(bytes, 0, bytes.length, 0);
    }
}