package io.opendrs.migration.service;

import java.util.regex.Pattern;

/** Simple {@code *} / {@code ?} glob matching for table selection (not SQL LIKE). */
final class NameGlobs {

    private NameGlobs() {
    }

    public static boolean isPattern(String value) {
        return value != null && (value.indexOf('*') >= 0 || value.indexOf('?') >= 0);
    }

    public static boolean matches(String name, String pattern) {
        if (name == null || pattern == null) {
            return false;
        }
        return name.matches(toRegex(pattern));
    }

    private static String toRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append(".");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }
}
