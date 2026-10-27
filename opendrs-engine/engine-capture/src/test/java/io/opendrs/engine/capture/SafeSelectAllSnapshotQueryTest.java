package io.opendrs.engine.capture;

import static org.assertj.core.api.Assertions.assertThat;

import io.debezium.connector.mysql.snapshot.query.SelectAllSnapshotQuery;
import java.util.List;
import org.junit.jupiter.api.Test;

class SafeSelectAllSnapshotQueryTest {

    private final SafeSelectAllSnapshotQuery query = new SafeSelectAllSnapshotQuery();
    private final SelectAllSnapshotQuery selectAll = new SelectAllSnapshotQuery();

    @Test
    void skipsTheSingleStarWarmupProbe() {
        // Exactly the call shape of BinlogSnapshotChangeEventSource#getSnapshotConnectionFirstSelect.
        assertThat(query.snapshotQuery("`db`.`t`", List.of("*"))).isEmpty();
    }

    @Test
    void realColumnListsAreByteForByteIdenticalToSelectAll() {
        // The whole point: real table reads must be indistinguishable from Debezium's built-in
        // query, or snapshot behaviour silently changes. Compared against the real
        // SelectAllSnapshotQuery so a Debezium upgrade that changes its SQL fails this test.
        List<List<String>> cases = List.of(
                List.of("id"),
                List.of("id", "name", "money"),
                List.of("`weird col`", "`select`"));
        for (List<String> columns : cases) {
            assertThat(query.snapshotQuery("`db`.`wallet`", columns))
                    .isPresent()
                    .isEqualTo(selectAll.snapshotQuery("`db`.`wallet`", columns));
        }
    }

    @Test
    void nameIsTheClassNameMatchedByDebeziumServiceLoader() {
        assertThat(query.name()).isEqualTo(SafeSelectAllSnapshotQuery.class.getName());
        // And it must be a name nobody else claims, or the ServiceLoader match is ambiguous.
        assertThat(selectAll.name()).isNotEqualTo(query.name());
    }
}
