package io.opendrs.engine.capture;

import static org.assertj.core.api.Assertions.assertThat;

import io.debezium.config.Configuration;
import io.debezium.connector.mysql.MySqlConnectorConfig;
import io.debezium.relational.TableId;
import io.debezium.relational.Tables.TableFilter;
import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.engine.capture.DebeziumEngineConfig.EngineSpec;
import io.opendrs.engine.replay.mapping.TableSelection;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Feeds the generated properties into Debezium's own {@link MySqlConnectorConfig} and asks its
 * filter what it would capture. This is the assertion that really matters: the include and exclude
 * lists are regular expressions, and a quoting mistake would either capture a whole database or
 * nothing at all — silently.
 */
class DebeziumSelectionFilterTest {

    @Test
    void tableModeCapturesOnlyTheSelectedTables() {
        TableFilter filter = filterFor(TableSelection.ofTables(List.of(table("test", "orders"))));

        assertThat(filter.isIncluded(TableId.parse("test.orders"))).isTrue();
        assertThat(filter.isIncluded(TableId.parse("test.users"))).isFalse();
        assertThat(filter.isIncluded(TableId.parse("other.orders"))).isFalse();
    }

    @Test
    void schemaModeCapturesEveryTableOfTheSelectedSchemas() {
        TableFilter filter = filterFor(TableSelection.ofSchemas(List.of("test", "sales")));

        assertThat(filter.isIncluded(TableId.parse("test.orders"))).isTrue();
        assertThat(filter.isIncluded(TableId.parse("test.anything_else"))).isTrue();
        assertThat(filter.isIncluded(TableId.parse("sales.orders"))).isTrue();
        assertThat(filter.isIncluded(TableId.parse("other.orders"))).isFalse();
    }

    @Test
    void schemaModeSubtractsExcludedTables() {
        TableSelection selection =
                new TableSelection(List.of("test"), null, null, List.of(table("test", "tmp_skip")), null);

        TableFilter filter = filterFor(selection);

        assertThat(filter.isIncluded(TableId.parse("test.orders"))).isTrue();
        assertThat(filter.isIncluded(TableId.parse("test.tmp_skip"))).isFalse();
    }

    @Test
    void wholeInstanceCapturesEverythingExceptTheExclusions() {
        TableSelection selection =
                new TableSelection(null, null, List.of("tmp"), List.of(table("test", "tmp_skip")), null);

        TableFilter filter = filterFor(selection);

        assertThat(filter.isIncluded(TableId.parse("test.orders"))).isTrue();
        assertThat(filter.isIncluded(TableId.parse("brand_new.table_created_later"))).isTrue();
        assertThat(filter.isIncluded(TableId.parse("test.tmp_skip"))).isFalse();
        assertThat(filter.isIncluded(TableId.parse("tmp.anything"))).isFalse();
    }

    @Test
    void identifiersWithRegexMetacharactersStayLiteral() {
        TableFilter filter = filterFor(TableSelection.ofTables(List.of(table("test", "a.b"))));

        // A dotted table name cannot go through TableId.parse (it would split), so build it.
        assertThat(filter.isIncluded(new TableId("test", null, "a.b"))).isTrue();
        assertThat(filter.isIncluded(new TableId("test", null, "axb"))).isFalse();
    }

    @Test
    void generatedPropertiesPassConnectorValidation() {
        Properties props = DebeziumEngineConfig.capture(new EngineSpec(
                7L, mysql(), List.of(new Table(new TableRef("test", "orders"))), 85744L, null,
                TableSelection.ofTables(List.of(table("test", "orders")))));

        List<String> problems = new ArrayList<>();
        new MySqlConnectorConfig(Configuration.from(props))
                .validateAndRecord(MySqlConnectorConfig.ALL_FIELDS, problems::add);

        assertThat(problems).isEmpty();
    }

    /**
     * Table-level capture lists the <em>expanded</em> tables ({@code spec.tables()}), not the raw
     * selection, so the fixture has to expand them the way the engine does.
     */
    private static TableFilter filterFor(TableSelection selection) {
        List<Table> expanded = selection.isSchemaLevel()
                ? List.of(new Table(new TableRef("test", "orders")))
                : selection.tables().stream()
                        .map(id -> new Table(new TableRef(id.getSchema(), id.getTable())))
                        .toList();
        Properties props = DebeziumEngineConfig.capture(
                new EngineSpec(7L, mysql(), expanded, 85744L, null, selection));
        return new MySqlConnectorConfig(Configuration.from(props))
                .getTableFilters()
                .dataCollectionFilter();
    }

    private static io.opendrs.common.metadata.TableId table(String schema, String name) {
        return io.opendrs.common.metadata.TableId.of(schema, name);
    }

    private static JdbcConnectionConfig mysql() {
        return JdbcConnectionConfig.builder()
                .type(DatabaseType.MYSQL)
                .host("10.0.0.1")
                .port(3306)
                .database("test")
                .username("cdc")
                .password("secret")
                .extras(java.util.Map.of("useSsl", false))
                .build();
    }
}
