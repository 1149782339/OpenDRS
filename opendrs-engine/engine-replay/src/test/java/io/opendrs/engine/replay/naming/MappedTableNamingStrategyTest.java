package io.opendrs.engine.replay.naming;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.engine.replay.mapping.MappingValidator;
import io.opendrs.engine.replay.mapping.SchemaMapping;
import io.opendrs.engine.replay.mapping.TableMapping;
import io.opendrs.engine.replay.mapping.TableMappings;
import io.opendrs.engine.replay.mapping.TableSelection;
import io.opendrs.common.metadata.TableId;
import java.util.List;
import org.junit.jupiter.api.Test;

class MappedTableNamingStrategyTest {

    private final MappingValidator validator = new MappingValidator();

    @Test
    void mapsMysqlCatalogThroughTableMapping() {
        TableSelection selection = new TableSelection(
                List.of("inventory"),
                null,
                null,
                null,
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
        TableSelection selection = TableSelection.ofSchemas(List.of("inventory"));
        MappedTableNamingStrategy strategy = new MappedTableNamingStrategy(validator, selection);
        TableId mapped = strategy.resolveTableId(new TableId("inventory", null, "customers"));
        assertThat(mapped.getSchema()).isEqualTo("inventory");
        assertThat(mapped.getTable()).isEqualTo("customers");
    }
}
