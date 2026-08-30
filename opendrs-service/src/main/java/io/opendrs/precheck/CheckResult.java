package io.opendrs.precheck;

import io.opendrs.jdbc.metadata.TableRef;

public record CheckResult(boolean ok, String name, String message, TableRef table) {

    public static CheckResult ok(String name, String message) {
        return new CheckResult(true, name, message, null);
    }

    public static CheckResult ok(String name, String message, TableRef table) {
        return new CheckResult(true, name, message, table);
    }

    public static CheckResult fail(String name, String message) {
        return new CheckResult(false, name, message, null);
    }

    public static CheckResult fail(String name, String message, TableRef table) {
        return new CheckResult(false, name, message, table);
    }
}
