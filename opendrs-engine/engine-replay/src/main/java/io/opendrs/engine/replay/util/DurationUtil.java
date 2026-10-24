package io.opendrs.engine.replay.util;

import java.util.concurrent.TimeUnit;

/**
 * Duration Util
 *
 */
public class DurationUtil {

    /**
     * Convert nanosecond duration to interval string
     * like '2 year 2 mons 2 days 23:56:2.123456789'
     *
     * @param nanosDuration
     * @return interval string
     */
    public static String toInterval(long nanosDuration) {
        boolean isMinus = false;
        if (nanosDuration < 0) {
            isMinus = true;
            nanosDuration = -nanosDuration;
        }
        long day = TimeUnit.NANOSECONDS.toDays(nanosDuration);
        long year = day / 365;
        day %= 365;
        long month = day / 30;
        day %= 30;
        long hour = TimeUnit.NANOSECONDS.toHours(nanosDuration)
            - TimeUnit.DAYS.toHours(TimeUnit.NANOSECONDS.toDays(nanosDuration));
        long minutes = TimeUnit.NANOSECONDS.toMinutes(nanosDuration)
            - TimeUnit.HOURS.toMinutes(TimeUnit.NANOSECONDS.toHours(nanosDuration));
        long sec = TimeUnit.NANOSECONDS.toSeconds(nanosDuration)
            - TimeUnit.MINUTES.toSeconds(TimeUnit.NANOSECONDS.toMinutes(nanosDuration));
        long nanos = TimeUnit.NANOSECONDS.toNanos(nanosDuration)
            - TimeUnit.SECONDS.toNanos(TimeUnit.NANOSECONDS.toSeconds(nanosDuration));
        if (isMinus) {
            if (year > 0) {
                year = -year;
            } else if (month > 0) {
                month = -month;
            } else if (day > 0) {
                day = -day;
            } else if (hour > 0) {
                hour = -hour;
            } else if (minutes > 0) {
                minutes = -minutes;
            } else {
                sec = -sec;
            }
        }
        return  String.format("%d year %d mons %d days %d:%d:%d.%09d", year, month, day, hour, minutes, sec, nanos);
    }

    /**
     * Convert nanosecond duration to interval string
     * like '43:56:2.123456789'
     *
     * @param nanosDuration
     * @return interval string
     */
    public static String toTime(long nanosDuration) {
        boolean isMinus = false;
        if (nanosDuration < 0) {
            isMinus = true;
            nanosDuration = -nanosDuration;
        }
        long hour = TimeUnit.NANOSECONDS.toHours(nanosDuration);
        long minutes = TimeUnit.NANOSECONDS.toMinutes(nanosDuration)
            - TimeUnit.HOURS.toMinutes(TimeUnit.NANOSECONDS.toHours(nanosDuration));
        long sec = TimeUnit.NANOSECONDS.toSeconds(nanosDuration)
            - TimeUnit.MINUTES.toSeconds(TimeUnit.NANOSECONDS.toMinutes(nanosDuration));
        long nanos = TimeUnit.NANOSECONDS.toNanos(nanosDuration)
            - TimeUnit.SECONDS.toNanos(TimeUnit.NANOSECONDS.toSeconds(nanosDuration));
        if (isMinus) {
            if (hour > 0) {
                hour = -hour;
            } else if (minutes > 0) {
                minutes = -minutes;
            } else {
                sec = -sec;
            }
        }
        return  String.format("%d:%d:%d.%09d", hour, minutes, sec, nanos);
    }
}
