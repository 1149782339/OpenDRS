package io.opendrs.engine.jdbc.binlog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BinlogPositionTest {

    private static final String UUID_A = "3e11fa47-71ca-11e1-9e33-c80aa9429562";
    private static final String UUID_B = "8f2c1d3a-0b1c-11ee-8a5e-0242ac120002";

    @Test
    void gtidContainmentDecidesWhenBothSidesHaveGtids() {
        BinlogPosition p1 = new BinlogPosition("mysql-bin.000003", 900, UUID_A + ":1-100,\n" + UUID_B + ":1-5");

        assertThat(p1.reachedBy(offset("mysql-bin.000003", 100, UUID_A + ":1-99," + UUID_B + ":1-5"))).isFalse();
        assertThat(p1.reachedBy(offset("mysql-bin.000003", 100, UUID_A + ":1-100," + UUID_B + ":1-5"))).isTrue();
        // GTID wins over file/pos: a later file without the GTIDs is not "reached".
        assertThat(p1.reachedBy(offset("mysql-bin.000009", 4, UUID_A + ":1-100"))).isFalse();
        assertThat(p1.reachedBy(offset("mysql-bin.000001", 4, UUID_A + ":1-120," + UUID_B + ":1-7"))).isTrue();
    }

    @Test
    void fileAndPositionDecideWithoutGtids() {
        BinlogPosition p1 = new BinlogPosition("mysql-bin.000009", 500, "");

        assertThat(p1.reachedBy(offset("mysql-bin.000009", 499, null))).isFalse();
        assertThat(p1.reachedBy(offset("mysql-bin.000009", 500, null))).isTrue();
        assertThat(p1.reachedBy(offset("mysql-bin.000010", 4, null))).isTrue();
        assertThat(p1.reachedBy(offset("mysql-bin.000008", 99999, null))).isFalse();
    }

    @Test
    void fileSuffixIsComparedNumerically() {
        BinlogPosition p1 = new BinlogPosition("mysql-bin.999999", 10, "");
        assertThat(p1.reachedBy(offset("mysql-bin.1000000", 4, null))).isTrue();
    }

    @Test
    void snapshotOffsetsNeverCount() {
        BinlogPosition p1 = new BinlogPosition("mysql-bin.000001", 100, "");
        Map<String, Object> snapshot = offset("mysql-bin.000001", 100, null);
        snapshot.put("snapshot", true);
        assertThat(p1.reachedBy(snapshot)).isFalse();
        snapshot.put("snapshot", "last");
        assertThat(p1.reachedBy(snapshot)).isFalse();
        snapshot.put("snapshot", "false");
        assertThat(p1.reachedBy(snapshot)).isTrue();
    }

    @Test
    void offsetsWithoutCoordinatesAreIgnored() {
        BinlogPosition p1 = new BinlogPosition("mysql-bin.000001", 100, "");
        assertThat(p1.reachedBy((Map<String, ?>) null)).isFalse();
        assertThat(p1.reachedBy(Map.of("ts_sec", 1L))).isFalse();
    }

    @Test
    void mapRoundTrip() {
        BinlogPosition p1 = new BinlogPosition("mysql-bin.000001", 100, UUID_A + ":1-3");
        assertThat(BinlogPosition.fromMap(p1.toMap())).isEqualTo(p1);
    }

    @Test
    void gtidSetParsesTagsGapsAndNewlines() {
        GtidSet set = GtidSet.parse(UUID_A + ":1-3:5-9,\n" + UUID_B + ":tag1:1-4");
        assertThat(set.contains(GtidSet.parse(UUID_A + ":2-3:6"))).isTrue();
        assertThat(set.contains(GtidSet.parse(UUID_A + ":4"))).isFalse();
        assertThat(set.contains(GtidSet.parse(UUID_B + ":tag1:2"))).isTrue();
        assertThat(set.contains(GtidSet.parse(UUID_B + ":2"))).isFalse();
        assertThat(GtidSet.parse(UUID_A + ":1-3:4-6").toString()).isEqualTo(UUID_A + ":1-6");
        assertThat(GtidSet.parse("").contains(GtidSet.parse(""))).isTrue();
    }

    private static Map<String, Object> offset(String file, long pos, String gtids) {
        Map<String, Object> offset = new HashMap<>();
        offset.put("file", file);
        offset.put("pos", pos);
        if (gtids != null) {
            offset.put("gtids", gtids);
        }
        return offset;
    }
}
