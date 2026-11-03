package io.opendrs.engine.schema.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opendrs.common.metadata.TableId;
import io.opendrs.engine.schema.convert.ConversionResult;
import io.opendrs.engine.schema.convert.ConversionStatus;
import io.opendrs.engine.schema.meta.ObjectId;
import io.opendrs.engine.schema.meta.ObjectType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ImportResultsTest {

    @Test
    void entriesAreASnapshotNotALiveView() {
        ImportResults results = new ImportResults();
        ObjectId first = table("a");
        results.put(first, succeeded());
        List<Map.Entry<ObjectId, ConversionResult>> snapshot = results.entries();

        results.put(table("b"), succeeded());

        assertThat(snapshot).hasSize(1);
        assertThat(results.entries()).hasSize(2);
    }

    @Test
    void entriesCannotWriteBackIntoTheResults() {
        ImportResults results = new ImportResults();
        results.put(table("a"), succeeded());

        Map.Entry<ObjectId, ConversionResult> entry = results.entries().get(0);

        assertThatThrownBy(() -> entry.setValue(succeeded()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static ObjectId table(String name) {
        return new ObjectId(TableId.of("shop", name), ObjectType.TABLE);
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
