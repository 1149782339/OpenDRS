package io.opendrs.engine.replay.util;

/**
 * Boolean Util
 *
 */
public class BooleanUtil {
    /**
     * Get boolean from int value
     *
     * @param value value
     * @return boolean value
     */
    public static boolean toBoolean(int value) {
        if (value == 1) {
            return true;
        } else if (value == 0) {
            return false;
        } else {
            throw new IllegalArgumentException("value \"" + value + "\" can't be converted to boolean");
        }
    }
}
