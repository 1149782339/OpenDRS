package io.opendrs.engine.replay;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Monotonic sequence for the synthetic full-load GTID namespace of one direction.
 *
 * <p>The next value is derived from the target's {@code @@global.gtid_executed} on first use, so a
 * restarted full load resumes where the previous attempt left off. Consumed numbers are never
 * reused even after a rollback (the counter simply moves on; gaps are harmless because nothing
 * else writes this namespace), and re-injecting an already-executed GTID is silently skipped by
 * MySQL, which is what makes crash-replay idempotent.
 */
public final class GtidSequence {

    private final String namespace;
    private long next = -1;

    public GtidSequence(String namespace) {
        this.namespace = namespace;
    }

    /** Next GTID ('namespace:n') for a snapshot batch applied on this connection. */
    public synchronized String next(Connection connection) throws SQLException {
        if (next < 0) {
            next = loadHighWaterMark(connection) + 1;
        }
        return namespace + ":" + next++;
    }

    /** Highest interval end the target has executed for this namespace, 0 when none. */
    private long loadHighWaterMark(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT @@GLOBAL.gtid_executed")) {
            if (!rs.next()) {
                return 0;
            }
            String executed = rs.getString(1);
            if (executed == null || executed.isBlank()) {
                return 0;
            }
            long high = 0;
            for (String set : executed.split(",")) {
                if (!set.startsWith(namespace + ":")) {
                    continue;
                }
                String intervals = set.substring(namespace.length() + 1);
                for (String interval : intervals.split(":")) {
                    String end = interval.contains("-") ? interval.substring(interval.indexOf('-') + 1) : interval;
                    high = Math.max(high, Long.parseLong(end.trim()));
                }
            }
            return high;
        }
    }
}
