package io.opendrs.engine.replay.util;

import org.apache.kafka.connect.data.Schema;

/**
 * String util
 *
 * <p>Only {@link #toString(Object, Schema)} is kept, used by the write set to render primary key
 * values into a stable conflict key.
 *
 */
public class StringUtil {

    /**
     * Render a Connect value as a stable string for conflict detection. Two equal key values must
     * render identically within one JVM run; cross-run stability is not required (the write set
     * never outlives the process).
     */
    public static String toString(Object value, Schema schema) {
        if (value == null) {
            return null;
        }
        return value.toString();
    }
}
