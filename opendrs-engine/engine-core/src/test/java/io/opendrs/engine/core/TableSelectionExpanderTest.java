package io.opendrs.engine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opendrs.engine.common.metadata.DatabaseType;
import io.opendrs.engine.common.metadata.TableId;
import io.opendrs.engine.common.selection.TableSelection;
import io.opendrs.engine.jdbc.JdbcConnection;
import io.opendrs.engine.jdbc.JdbcConnectionConfig;
import io.opendrs.engine.jdbc.JdbcConnectionFactory;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TableSelectionExpanderTest {

    private JdbcConnectionFactory factory;
    private JdbcConnection conn;
    private TableSelectionExpander expander;
    private JdbcConnectionConfig source;

    @BeforeEach
    void setUp() {
        factory = Mockito.mock(JdbcConnectionFactory.class);
        conn = Mockito.mock(JdbcConnection.class);
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
    void explicitTablesSkipJdbcAndHonorExclude() throws Exception {
        TableSelection selection = new TableSelection(
                null,
                List.of(table("hr", "emp"), table("hr", "dept"), table("hr", "tmp_skip")),
                null,
                List.of(table("hr", "tmp_skip")),
                null);

        List<TableId> tables = expander.expand(source, selection);
        assertEquals(Set.of("emp", "dept"), names(tables));
        verifyNoInteractions(factory);
        verifyNoInteractions(conn);
    }

    @Test
    void wholeSchemaListsLiveTablesAndSubtractsExcludes() throws Exception {
        when(conn.listTables(eq("hr"), any()))
                .thenReturn(List.of(
                        TableId.of("hr", "emp"),
                        TableId.of("hr", "dept"),
                        TableId.of("hr", "tmp_skip")));

        TableSelection selection = new TableSelection(
                List.of("hr"),
                null,
                null,
                List.of(table("hr", "dept"), table("hr", "tmp_skip")),
                null);

        List<TableId> tables = expander.expand(source, selection);
        assertEquals(Set.of("emp"), names(tables));
        // Excludes are applied after listing: one rule for both shapes.
        verify(conn).listTables(eq("hr"), eq(List.of()));
    }

    @Test
    void everythingModeListsEverySchemaTheConnectionReports() throws Exception {
        when(conn.listSchemas()).thenReturn(List.of("hr", "sales"));
        when(conn.listTables(eq("hr"), any())).thenReturn(List.of(TableId.of("hr", "emp")));
        when(conn.listTables(eq("sales"), any())).thenReturn(List.of(TableId.of("sales", "orders")));

        List<TableId> tables = expander.expand(source, TableSelection.all());
        assertEquals(Set.of("emp", "orders"), names(tables));
        assertEquals(Set.of("hr", "sales"), schemas(tables));
    }

    @Test
    void everythingModeSkipsExcludedSchemas() throws Exception {
        when(conn.listSchemas()).thenReturn(List.of("hr", "sales"));
        when(conn.listTables(eq("hr"), any())).thenReturn(List.of(TableId.of("hr", "emp")));

        TableSelection selection = new TableSelection(null, null, List.of("sales"), null, null);

        List<TableId> tables = expander.expand(source, selection);
        assertEquals(Set.of("emp"), names(tables));
        verify(conn, never()).listTables(eq("sales"), any());
    }

    @Test
    void nullSelectionMeansEverything() throws Exception {
        when(conn.listSchemas()).thenReturn(List.of("hr"));
        when(conn.listTables(eq("hr"), any())).thenReturn(List.of(TableId.of("hr", "emp")));

        List<TableId> tables = expander.expand(source, null);
        assertEquals(Set.of("emp"), names(tables));
    }

    @Test
    void expandExplicitCoversNamedTablesOnly() {
        assertTrue(expander.expandExplicit(
                new TableSelection(List.of("hr"), null, null, null, null)).isEmpty());
        assertTrue(expander.expandExplicit(TableSelection.all()).isEmpty());

        TableSelection named = new TableSelection(
                null,
                List.of(table("sales", "orders"), table("sales", "skip")),
                null,
                List.of(table("sales", "skip")),
                null);
        List<TableId> tables = expander.expandExplicit(named);
        assertEquals(Set.of("orders"), names(tables));
        assertEquals("sales", tables.getFirst().getSchema());
    }

    @Test
    void explicitSelectionIgnoresTheExcludedSchema() throws Exception {
        TableSelection selection = new TableSelection(
                null,
                List.of(table("hr", "emp"), table("tmp", "x")),
                List.of("tmp"),
                null,
                null);

        List<TableId> tables = expander.expand(source, selection);
        assertEquals(Set.of("emp"), names(tables));
        verifyNoInteractions(factory);
    }

    private static TableId table(String schema, String table) {
        return TableId.of(schema, table);
    }

    private static Set<String> names(List<TableId> tables) {
        return tables.stream().map(TableId::getTable).collect(Collectors.toSet());
    }

    private static Set<String> schemas(List<TableId> tables) {
        return tables.stream().map(TableId::getSchema).collect(Collectors.toSet());
    }
}
