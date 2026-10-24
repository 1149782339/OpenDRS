package io.opendrs.engine.precheck.fact;

/**
 * The endpoint's session time zone plus the UTC offset it resolves to right now. The offset is what
 * makes two differently-named zones comparable: {@code CST} and {@code Asia/Shanghai} agree only if
 * they currently point at the same moment.
 */
public record TimezoneInfo(String name, int offsetSeconds) {

    public String offsetText() {
        int total = Math.abs(offsetSeconds);
        String sign = offsetSeconds < 0 ? "-" : "+";
        return String.format("%s%02d:%02d", sign, total / 3600, (total % 3600) / 60);
    }

    public String display() {
        return (name == null || name.isBlank() ? "未知" : name) + "(" + offsetText() + ")";
    }
}
