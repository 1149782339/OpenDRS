package io.opendrs.engine.capture;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public final class ByteBuffers {

    private ByteBuffers() {
    }

    public static byte[] toBytes(ByteBuffer buffer) {
        if (buffer == null) {
            return null;
        }
        ByteBuffer copy = buffer.asReadOnlyBuffer();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    public static String toUtf8(ByteBuffer buffer) {
        byte[] bytes = toBytes(buffer);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    public static ByteBuffer fromUtf8(String value) {
        if (value == null) {
            return null;
        }
        return ByteBuffer.wrap(value.getBytes(StandardCharsets.UTF_8));
    }
}
