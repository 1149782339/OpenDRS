package io.opendrs.engine.schema.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendrs.engine.schema.meta.DatabaseObject;
import io.opendrs.engine.schema.meta.ObjectId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversionResultsTest {

    @Test
    void entriesAreASnapshotNotALiveView() {
        ConversionResults results = new ConversionResults();
        results.put(object("a"), succeeded());
        List<Map.Entry<DatabaseObject, ConversionResult>> snapshot = results.entries();

        results.put(object("b"), succeeded());

        assertThat(snapshot).hasSize(1);
        assertThat(results.entries()).hasSize(2);
    }

    @Test
    void entriesCannotWriteBackIntoTheResults() {
        ConversionResults results = new ConversionResults();
        results.put(object("a"), succeeded());

        Map.Entry<DatabaseObject, ConversionResult> entry = results.entries().get(0);

        assertThatThrownBy(() -> entry.setValue(succeeded()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static DatabaseObject object(String name) {
        return new DatabaseObject(
                ObjectId.table("shop", name), "CREATE TABLE shop.t (id int)");
    }

    private static ConversionResult succeeded() {
        return ConversionResult.builder()
                .status(ConversionStatus.SUCCEEDED)
                .statements(List.of("CREATE TABLE shop.t (id int)"))
                .infos(List.of())
                .warnings(List.of())
                .error("")
                .build();
    }
}
