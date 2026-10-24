package io.opendrs.engine.precheck.fact;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A database server version parsed into comparable numbers. The raw string is kept because it is
 * what the user sees and what support needs ("8.0.36-log", "17.2 (Debian 17.2-1)").
 */
public record ServerVersion(String raw, int major, int minor, int patch) {

    private static final Pattern LEADING = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

    public static ServerVersion parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        Matcher matcher = LEADING.matcher(text);
        if (!matcher.find()) {
            return new ServerVersion(text, 0, 0, 0);
        }
        return new ServerVersion(
                text, number(matcher.group(1)), number(matcher.group(2)), number(matcher.group(3)));
    }

    private static int number(String value) {
        return value == null ? 0 : Integer.parseInt(value);
    }

    /** {@code major.minor} comparison; the patch level never decides support. */
    public boolean atLeast(int requiredMajor, int requiredMinor) {
        if (major != requiredMajor) {
            return major > requiredMajor;
        }
        return minor >= requiredMinor;
    }

    public String display() {
        if (patch > 0) {
            return major + "." + minor + "." + patch;
        }
        return major + "." + minor;
    }
}
