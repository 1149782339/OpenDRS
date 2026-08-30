package io.opendrs.debezium;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.migration.api.request.SchemaMapping;
import io.opendrs.migration.api.request.SchemaObject;
import io.opendrs.migration.api.request.TableMapping;
import io.opendrs.migration.api.request.TableMappings;
import io.opendrs.migration.api.request.TableSelection;
import io.opendrs.migration.service.MappingValidator;
import io.opendrs.sink.relation.TableId;
import java.util.List;
import org.junit.jupiter.api.Test;

class MappedTableNamingStrategyTest {

    private final MappingValidator validator = new MappingValidator();

    @Test
    void mapsMysqlCatalogThroughTableMapping() {
        TableSelection selection = new TableSelection(
                List.of(new SchemaObject("inventory", List.of("customers"), null, null)),
                new TableMappings(
                        List.of(new SchemaMapping("inventory", "public")),
                        List.of(new TableMapping("inventory", "customers", "public", "cust"))));
        MappedTableNamingStrategy strategy = new MappedTableNamingStrategy(validator, selection);
        TableId mapped = strategy.resolveTableId(new TableId("inventory", null, "customers"));
        assertThat(mapped.getCatalog()).isNull();
        assertThat(mapped.getSchema()).isEqualTo("public");
        assertThat(mapped.getTable()).isEqualTo("cust");
    }

    @Test
    void identityWhenNoMappings() {
        TableSelection selection =
                new TableSelection(List.of(new SchemaObject("inventory", List.of("customers"), null, null)), null);
        MappedTableNamingStrategy strategy = new MappedTableNamingStrategy(validator, selection);
        TableId mapped = strategy.resolveTableId(new TableId("inventory", null, "customers"));
        assertThat(mapped.getSchema()).isEqualTo("inventory");
        assertThat(mapped.getTable()).isEqualTo("customers");
    }
}
