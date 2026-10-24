package io.opendrs.engine.replay;

import java.util.Set;

/**
 * Bidirectional-sync loop prevention for one direction of a task.
 *
 * <p>The sink tags every transaction it applies on the target with a GTID (see
 * {@link io.opendrs.engine.replay.applier.JdbcApplier}): incremental batches replay the source
 * record's own GTID, snapshot batches use a synthetic {@code fullLoadNamespace}. The capture side
 * of the <em>opposite</em> direction drops any binlog transaction whose GTID UUID belongs to this
 * drop set, which is what breaks the write-back loop.
 *
 * @param dropGtidUuids GTID UUIDs to drop on capture: the peer server's {@code server_uuid}
 *     (its transactions replayed by the opposite sink) and the opposite direction's synthetic
 *     full-load namespace
 * @param fullLoadNamespace synthetic GTID UUID this direction's sink stamps on snapshot batches;
 *     null disables GTID tagging entirely (single-direction tasks)
 */
public record GtidLoopConfig(Set<String> dropGtidUuids, String fullLoadNamespace) {

    public static GtidLoopConfig disabled() {
        return new GtidLoopConfig(Set.of(), null);
    }

    public boolean taggingEnabled() {
        return fullLoadNamespace != null;
    }

    /** True when the GTID ('uuid:seq') is one this capture side must not replay. */
    public boolean isLoopBack(String gtid) {
        return gtid != null && dropGtidUuids.contains(uuidOf(gtid));
    }

    /** '3E11FA47-...-C80AA9429562:23' -&gt; '3E11FA47-...-C80AA9429562'. */
    public static String uuidOf(String gtid) {
        int colon = gtid.lastIndexOf(':');
        return colon < 0 ? gtid : gtid.substring(0, colon);
    }
}
