package io.opendrs.engine.jdbc.binlog;

import io.opendrs.engine.jdbc.JdbcConnection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A MySQL source position: binlog file, byte offset and the executed GTID set (empty when GTIDs
 * are off).
 *
 * <p>The deferred-object restore uses it as <em>P1</em>, read right after every snapshot chunk
 * finished: a parallel {@code snapshot.locking.mode=none} snapshot is stitched from several read
 * views, so the target only matches one real source instant once replay has passed P1.
 * {@link #reachedBy(Map)} answers that question for one applied Debezium offset.
 */
public record BinlogPosition(String file, long pos, String gtidSet) {

    public BinlogPosition {
        Objects.requireNonNull(file, "file");
        gtidSet = gtidSet == null ? "" : gtidSet.replace("\n", "").replace("\r", "").trim();
    }

    /**
     * Current end of the source binlog. {@code SHOW MASTER STATUS} works up to 8.x and is gone
     * in 8.4+, where {@code SHOW BINARY LOG STATUS} replaces it; try the old spelling first.
     *
     * @return null when the binlog is disabled
     */
    public static BinlogPosition read(JdbcConnection source) throws SQLException {
        SQLException first = null;
        for (String sql : new String[]{"SHOW MASTER STATUS", "SHOW BINARY LOG STATUS"}) {
            try {
                return source.queryOne(sql, rs -> {
                    String file = rs.getString("File");
                    if (file == null) {
                        return null;
                    }
                    String gtids;
                    try {
                        gtids = rs.getString("Executed_Gtid_Set");
                    } catch (SQLException missing) {
                        gtids = "";
                    }
                    return new BinlogPosition(file, rs.getLong("Position"), gtids);
                });
            } catch (SQLException ex) {
                if (first == null) {
                    first = ex;
                }
            }
        }
        throw first;
    }

    /**
     * The applied position a Debezium MySQL source offset stands for, or null when the offset
     * carries no binlog coordinates or is a snapshot offset (snapshot offsets repeat the
     * snapshot start position and say nothing about streaming progress).
     */
    public static BinlogPosition fromOffset(Map<String, ?> offset) {
        if (offset == null) {
            return null;
        }
        Object snapshot = offset.get("snapshot");
        if (snapshot != null && !"false".equalsIgnoreCase(String.valueOf(snapshot))) {
            return null;
        }
        Object file = offset.get("file");
        Object pos = offset.get("pos");
        if (!(file instanceof String f) || f.isBlank() || pos == null) {
            return null;
        }
        long position;
        try {
            position = pos instanceof Number number ? number.longValue() : Long.parseLong(pos.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
        Object gtids = offset.get("gtids");
        return new BinlogPosition(f, position, gtids == null ? "" : gtids.toString());
    }

    /**
     * True when an applied position has reached this one.
     *
     * <p>GTID containment wins when both sides carry a GTID set: Debezium's {@code gtids} lists
     * the transactions completed before the record's own transaction, so containing every GTID
     * of this position means each transaction up to it is applied. Otherwise the binlog file is
     * compared by its numeric suffix, then the offset: Debezium's {@code pos} is the restart
     * position (start of the transaction in progress), so {@code pos >= this.pos} means every
     * transaction that ended at or before this position is done. Heartbeat offsets advance the
     * same fields, so a quiet source still reaches P1.
     */
    public boolean reachedBy(BinlogPosition applied) {
        if (applied == null) {
            return false;
        }
        if (!gtidSet.isEmpty() && !applied.gtidSet().isEmpty()) {
            return GtidSet.parse(applied.gtidSet()).contains(GtidSet.parse(gtidSet));
        }
        int byFile = compareFiles(applied.file(), file);
        if (byFile != 0) {
            return byFile > 0;
        }
        return applied.pos() >= pos;
    }

    public boolean reachedBy(Map<String, ?> appliedOffset) {
        return reachedBy(fromOffset(appliedOffset));
    }

    /** Compares binlog file names by their numeric suffix ({@code mysql-bin.000010} after {@code .000009}). */
    static int compareFiles(String a, String b) {
        long na = suffix(a);
        long nb = suffix(b);
        if (na >= 0 && nb >= 0) {
            return Long.compare(na, nb);
        }
        return a.compareTo(b);
    }

    private static long suffix(String file) {
        int dot = file.lastIndexOf('.');
        if (dot < 0 || dot == file.length() - 1) {
            return -1;
        }
        try {
            return Long.parseLong(file.substring(dot + 1));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("file", file);
        map.put("pos", pos);
        map.put("gtids", gtidSet);
        return map;
    }

    public static BinlogPosition fromMap(Map<String, ?> map) {
        if (map == null || map.get("file") == null || map.get("pos") == null) {
            return null;
        }
        Object pos = map.get("pos");
        long position = pos instanceof Number number ? number.longValue() : Long.parseLong(pos.toString());
        Object gtids = map.get("gtids");
        return new BinlogPosition(map.get("file").toString(), position, gtids == null ? "" : gtids.toString());
    }

    @Override
    public String toString() {
        return file + ":" + pos + (gtidSet.isEmpty() ? "" : " gtids=" + gtidSet);
    }
}
