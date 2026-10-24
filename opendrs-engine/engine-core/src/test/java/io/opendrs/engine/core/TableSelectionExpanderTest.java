package io.opendrs.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.common.metadata.TableId;
import io.opendrs.engine.replay.mapping.TableSelection;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.dialect.DbDialect;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TableSelectionExpanderTest {

    private JdbcConnectionFactory factory;
    private JdbcConnection conn;
    private DbDialect dialect;
    private TableSelectionExpander expander;
    private JdbcConnectionConfig source;

    @BeforeEach
    void setUp() {
        factory = Mockito.mock(JdbcConnectionFactory.class);
        conn = Mockito.mock(JdbcConnection.class);
        dialect = Mockito.mock(DbDialect.class);
        when(factory.open(any(JdbcConnectionConfig.class))).thenReturn(conn);
        expander = new TableSelectionExpander(factory);
        source = JdbcConnectionConfig.builder()
                .type(DatabaseType.MYSQL)
                .host("10.0.0.2")
                .port(3306)
                .database("hr")
                .build();
    }

    @Test
    void explicitTablesSkipJdbcAndHonorExclude() {
        TableSelection selection = new TableSelection(
                null,
                List.of(table("hr", "emp"), table("hr", "dept"), table("hr", "tmp_skip")),
                null,
                List.of(table("hr", "tmp_skip")),
                null);

        List<Table> tables = expander.expand(source, selection, dialect);
        assertEquals(Set.of("emp", "dept"), names(tables));
        Mockito.verifyNoInteractions(factory);
        Mockito.verifyNoInteractions(dialect);
    }

    @Test
    void wholeSchemaListsLiveTablesAndSubtractsExcludes() {
        when(dialect.listTables(eq(conn), eq("hr"), any()))
                .thenReturn(List.of(
                        new Table(new TableRef("hr", "emp")),
                        new Table(new TableRef("hr", "dept")),
                        new Table(new TableRef("hr", "tmp_skip"))));

        TableSelection selection = new TableSelection(
                List.of("hr"),
                null,
                null,
                List.of(table("hr", "dept"), table("hr", "tmp_skip")),
                null);

        List<Table> tables = expander.expand(source, selection, dialect);
        assertEquals(Set.of("emp"), names(tables));
        // Excludes are applied after listing: one rule for both shapes.
        verify(dialect).listTables(eq(conn), eq("hr"), eq(List.of()));
    }

    @Test
    void everythingModeListsEverySchemaTheDialectReports() {
        when(dialect.listSchemas(conn)).thenReturn(List.of("hr", "sales"));
        when(dialect.listTables(eq(conn), eq("hr"), any()))
                .thenReturn(List.of(new Table(new TableRef("hr", "emp"))));
        when(dialect.listTables(eq(conn), eq("sales"), any()))
                .thenReturn(List.of(new Table(new TableRef("sales", "orders"))));

        List<Table> tables = expander.expand(source, TableSelection.all(), dialect);
        assertEquals(Set.of("emp", "orders"), names(tables));
        assertEquals(Set.of("hr", "sales"), schemas(tables));
    }

    @Test
    void everythingModeSkipsExcludedSchemas() {
        when(dialect.listSchemas(conn)).thenReturn(List.of("hr", "sales"));
        when(dialect.listTables(eq(conn), eq("hr"), any()))
                .thenReturn(List.of(new Table(new TableRef("hr", "emp"))));

        TableSelection selection = new TableSelection(null, null, List.of("sales"), null, null);

        List<Table> tables = expander.expand(source, selection, dialect);
        assertEquals(Set.of("emp"), names(tables));
        verify(dialect, never()).listTables(eq(conn), eq("sales"), any());
    }

    @Test
    void nullSelectionMeansEverything() {
        when(dialect.listSchemas(conn)).thenReturn(List.of("hr"));
        when(dialect.listTables(eq(conn), eq("hr"), any()))
                .thenReturn(List.of(new Table(new TableRef("hr", "emp"))));

        List<Table> tables = expander.expand(source, null, dialect);
        assertEquals(Set.of("emp"), names(tables));
    }

    @Test
    void expandExplicitCoversNamedTablesOnly() {
        assertTrue(expander.expandExplicit(new TableSelection(List.of("hr"), null, null, null, null)).isEmpty());
        assertTrue(expander.expandExplicit(TableSelection.all()).isEmpty());

        TableSelection named = new TableSelection(
                null,
                List.of(table("sales", "orders"), table("sales", "skip")),
                null,
                List.of(table("sales", "skip")),
                null);
        List<Table> tables = expander.expandExplicit(named);
        assertEquals(Set.of("orders"), names(tables));
        assertEquals("sales", tables.getFirst().ref().schema());
    }

    @Test
    void explicitSelectionIgnoresTheExcludedSchema() {
        TableSelection selection = new TableSelection(
                null,
                List.of(table("hr", "emp"), table("tmp", "x")),
                List.of("tmp"),
                null,
                null);

        List<Table> tables = expander.expand(source, selection, dialect);
        assertEquals(Set.of("emp"), names(tables));
        Mockito.verifyNoInteractions(factory);
    }

    private static TableId table(String schema, String table) {
        return TableId.of(schema, table);
    }

    private static Set<String> names(List<Table> tables) {
        return tables.stream().map(table -> table.ref().table()).collect(Collectors.toSet());
    }

    private static Set<String> schemas(List<Table> tables) {
        return tables.stream().map(table -> table.ref().schema()).collect(Collectors.toSet());
    }
}
