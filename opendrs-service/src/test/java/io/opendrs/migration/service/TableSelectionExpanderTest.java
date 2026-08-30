package io.opendrs.migration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.jdbc.dialect.DbDialect;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import io.opendrs.migration.api.request.SchemaObject;
import io.opendrs.migration.api.request.TableSelection;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class TableSelectionExpanderTest {

    private JdbcConnectionFactory factory;
    private JdbcConnection conn;
    private DbDialect dialect;
    private TableSelectionExpander expander;
    private ConnectionInfo source;

    @BeforeEach
    void setUp() {
        factory = Mockito.mock(JdbcConnectionFactory.class);
        conn = Mockito.mock(JdbcConnection.class);
        dialect = Mockito.mock(DbDialect.class);
        when(factory.open(any(ConnectionInfo.class))).thenReturn(conn);
        expander = new TableSelectionExpander(factory);
        source = new ConnectionInfo();
        source.setType(DbType.MYSQL);
        source.setHost("10.0.0.2");
        source.setPort(3306);
        source.setDbName("hr");
    }

    @Test
    void explicitTablesSkipJdbcAndHonorExclude() {
        TableSelection selection = new TableSelection(
                List.of(new SchemaObject("hr", List.of("emp", "dept", "tmp_skip"), null, List.of("tmp_skip"))),
                null);

        List<Table> tables = expander.expand(source, selection, dialect);
        assertEquals(Set.of("emp", "dept"), names(tables));
        Mockito.verifyNoInteractions(factory);
        Mockito.verifyNoInteractions(dialect);
    }

    @Test
    void allTablesPassesExactExcludesToListTablesAndFiltersGlobs() {
        when(dialect.listTables(eq(conn), eq("hr"), any()))
                .thenReturn(List.of(
                        new Table(new TableRef("hr", "emp")),
                        new Table(new TableRef("hr", "TMP_A"))));

        TableSelection selection = new TableSelection(
                List.of(new SchemaObject("hr", null, true, List.of("dept", "TMP_*"))),
                null);

        List<Table> tables = expander.expand(source, selection, dialect);
        assertEquals(Set.of("emp"), names(tables));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TableRef>> excludes = ArgumentCaptor.forClass(List.class);
        verify(dialect).listTables(eq(conn), eq("hr"), excludes.capture());
        assertEquals(List.of(new TableRef("hr", "dept")), excludes.getValue());
        assertFalse(excludes.getValue().stream().anyMatch(ref -> NameGlobs.isPattern(ref.table())));
    }

    @Test
    void globInTablesListIsExpandedFromListTables() {
        when(dialect.listTables(eq(conn), eq("hr"), any()))
                .thenReturn(List.of(
                        new Table(new TableRef("hr", "emp")),
                        new Table(new TableRef("hr", "employees")),
                        new Table(new TableRef("hr", "TMP_A"))));

        TableSelection selection = new TableSelection(
                List.of(new SchemaObject("hr", List.of("emp*"), null, List.of("TMP_*"))),
                null);

        List<Table> tables = expander.expand(source, selection, dialect);
        assertEquals(Set.of("emp", "employees"), names(tables));
    }

    @Test
    void expandExplicitSkipsAllTablesAndGlobs() {
        TableSelection selection = new TableSelection(
                List.of(
                        new SchemaObject("hr", null, true, null),
                        new SchemaObject("sales", List.of("orders", "TMP_*"), null, null)),
                null);

        List<Table> tables = expander.expandExplicit(selection);
        assertEquals(Set.of("orders"), names(tables));
        assertEquals("sales", tables.getFirst().ref().schema());
    }

    @Test
    void nameGlobsMatchStarAndQuestion() {
        assertTrue(NameGlobs.isPattern("TMP_*"));
        assertTrue(NameGlobs.matches("TMP_A", "TMP_*"));
        assertFalse(NameGlobs.matches("emp", "TMP_*"));
        assertTrue(NameGlobs.matches("emp", "em?"));
        assertFalse(NameGlobs.isPattern("emp"));
    }

    private static Set<String> names(List<Table> tables) {
        return tables.stream().map(table -> table.ref().table()).collect(Collectors.toSet());
    }
}
