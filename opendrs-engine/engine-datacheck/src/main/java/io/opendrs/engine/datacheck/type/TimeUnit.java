package io.opendrs.engine.datacheck.type;

public final class TimeUnit {
    private TimeUnit() { }
    public static final short SECOND = 0;
    public static final short MILLISECOND = 1;
    public static final short MICROSECOND = 2;
    public static final short NANOSECOND = 3;

    public static final java.lang.String[] names = {
            "SECOND", "MILLISECOND", "MICROSECOND", "NANOSECOND",
    };

    public static java.lang.String name(int e) {
        return names[e];
    }
}
