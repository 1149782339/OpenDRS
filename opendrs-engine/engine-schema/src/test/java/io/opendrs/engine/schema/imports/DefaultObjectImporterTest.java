package io.opendrs.engine.schema.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opendrs.engine.schema.convert.ConversionResult;
import io.opendrs.engine.schema.convert.ConversionResults;
import io.opendrs.engine.schema.convert.ConversionStatus;
import io.opendrs.engine.schema.meta.DatabaseObject;
import io.opendrs.engine.schema.meta.ObjectId;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class DefaultObjectImporterTest {

    private Connection connection;
    private Statement statement;

    @BeforeEach
    void setUp() throws SQLException {
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement);
    }

    @Test
    void executesObjectsInBatchOrderCommittingEachOnItsOwn() throws SQLException {
        ObjectId customers = table("shop", "customers");
        ObjectId orders = table("shop", "orders");
        ConversionResults batch = new ConversionResults();
        add(batch, customers, converted("CREATE TABLE shop.customers (id int)"));
        add(batch, orders, converted("CREATE TABLE shop.orders (id int)", "CREATE INDEX orders_c ON shop.orders"));

        ImportResults results = new DefaultObjectImporter(connection).importObjects(batch, null);

        assertThat(results.size()).isEqualTo(2);
        assertThat(results.get(customers).getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        assertThat(results.get(orders).getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        InOrder order = inOrder(statement);
        order.verify(statement).execute("CREATE TABLE shop.customers (id int)");
        order.verify(statement).execute("CREATE TABLE shop.orders (id int)");
        order.verify(statement).execute("CREATE INDEX orders_c ON shop.orders");
        verify(connection, times(2)).commit();
        verify(connection, times(2)).setAutoCommit(false);
        verify(connection, times(2)).setAutoCommit(true);
    }

    @Test
    void recordsFailedObjectAndContinuesWithTheNext() throws SQLException {
        ObjectId broken = table("shop", "broken");
        ObjectId healthy = table("shop", "healthy");
        ConversionResults batch = new ConversionResults();
        add(batch, broken, converted("BAD"));
        add(batch, healthy, converted("CREATE TABLE shop.healthy (id int)"));
        doThrow(new SQLException("relation already exists", "42P07")).when(statement).execute("BAD");

        ImportResults results = new DefaultObjectImporter(connection).importObjects(batch, null);

        assertThat(results.get(broken).getStatus()).isEqualTo(ConversionStatus.FAILED);
        assertThat(results.get(broken).getErrors()).contains("42P07").contains("relation already exists");
        assertThat(results.get(broken).getStatements()).containsExactly("BAD");
        assertThat(results.get(healthy).getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        verify(connection).rollback();
        verify(connection, times(2)).setAutoCommit(true);
    }

    @Test
    void abortsBatchWhenTheConnectionItselfFailed() throws SQLException {
        ConversionResults batch = new ConversionResults();
        add(batch, table("shop", "a"), converted("BAD"));
        add(batch, table("shop", "b"), converted("CREATE TABLE shop.b (id int)"));
        doThrow(new SQLException("connection reset", "08006")).when(statement).execute("BAD");

        DefaultObjectImporter importer = new DefaultObjectImporter(connection);

        assertThatThrownBy(() -> importer.importObjects(batch, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("connection reset");
        verify(connection, never()).commit();
    }

    @Test
    void abortsBatchWhenRollbackFails() throws SQLException {
        ConversionResults batch = new ConversionResults();
        add(batch, table("shop", "a"), converted("BAD"));
        doThrow(new SQLException("syntax error", "42601")).when(statement).execute("BAD");
        doThrow(new SQLException("connection closed", "08003")).when(connection).rollback();

        DefaultObjectImporter importer = new DefaultObjectImporter(connection);

        assertThatThrownBy(() -> importer.importObjects(batch, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("rollback failed")
                .hasMessageContaining("connection closed");
    }

    @Test
    void carriesConversionFailuresOverWithoutExecutingThem() throws SQLException {
        ObjectId broken = table("shop", "broken");
        ObjectId dropped = table("shop", "dropped");
        ConversionResult failed = result(ConversionStatus.FAILED, List.of(), "unparsable DDL");
        ConversionResult droppedResult = result(ConversionStatus.DROPPED, List.of(), "");
        ConversionResults batch = new ConversionResults();
        add(batch, broken, failed);
        add(batch, dropped, droppedResult);

        ImportResults results = new DefaultObjectImporter(connection).importObjects(batch, null);

        assertThat(results.get(broken)).isSameAs(failed);
        assertThat(results.get(dropped)).isSameAs(droppedResult);
        verify(connection, never()).createStatement();
    }

    @Test
    void recordsObjectWithoutStatementsAsDropped() throws SQLException {
        ObjectId empty = table("shop", "empty");
        ConversionResults batch = new ConversionResults();
        add(batch, empty, converted());

        ImportResults results = new DefaultObjectImporter(connection).importObjects(batch, null);

        assertThat(results.get(empty).getStatus()).isEqualTo(ConversionStatus.DROPPED);
        verify(connection, never()).createStatement();
    }

    @Test
    void keepsConversionWarningsOnTheImportOutcome() throws SQLException {
        ObjectId id = table("shop", "t");
        ConversionResult conversion = ConversionResult.builder()
                .status(ConversionStatus.SUCCEEDED)
                .statements(List.of("CREATE TABLE shop.t (id int)"))
                .infos(List.of("note"))
                .warnings(List.of("AUTO_INCREMENT dropped"))
                .error("")
                .build();
        ConversionResults batch = new ConversionResults();
        add(batch, id, conversion);

        ImportResults results = new DefaultObjectImporter(connection).importObjects(batch, null);

        assertThat(results.get(id).getWarnings()).containsExactly("AUTO_INCREMENT dropped");
        assertThat(results.get(id).getInfos()).containsExactly("note");
        assertThat(results.get(id).getErrors()).isEmpty();
    }

    @Test
    void pointsTheConnectionAtTheMappedSchemaSwitchingOnlyWhenItChanges() throws SQLException {
        ConversionResults batch = new ConversionResults();
        add(batch, table("shop", "a"), converted("CREATE TABLE a (id int)"));
        add(batch, table("shop", "b"), converted("CREATE TABLE b (id int)"));
        add(batch, table("crm", "c"), converted("CREATE TABLE c (id int)"));
        ImportConfiguration configuration = ImportConfiguration.builder()
                .schemaNamingStrategy(schema -> schema + "_pg")
                .build();

        new DefaultObjectImporter(connection).importObjects(batch, configuration);

        InOrder order = inOrder(connection, statement);
        order.verify(connection).setSchema("shop_pg");
        order.verify(statement).execute("CREATE TABLE a (id int)");
        order.verify(statement).execute("CREATE TABLE b (id int)");
        order.verify(connection).setSchema("crm_pg");
        order.verify(statement).execute("CREATE TABLE c (id int)");
        verify(connection, times(2)).setSchema(anyString());
    }

    @Test
    void createsASchemaWithoutPointingTheConnectionAtIt() throws SQLException {
        ConversionResults batch = new ConversionResults();
        add(batch, ObjectId.schema("shop"), converted("CREATE SCHEMA \"shop\""));
        add(batch, table("shop", "a"), converted("CREATE TABLE a (id int)"));

        new DefaultObjectImporter(connection).importObjects(
                batch, ImportConfiguration.builder().schemaNamingStrategy(schema -> schema).build());

        InOrder order = inOrder(connection, statement);
        order.verify(statement).execute("CREATE SCHEMA \"shop\"");
        order.verify(connection).setSchema("shop");
        order.verify(statement).execute("CREATE TABLE a (id int)");
    }

    @Test
    void leavesTheConnectionSchemaAloneWithoutAStrategy() throws SQLException {
        ConversionResults batch = new ConversionResults();
        add(batch, table("shop", "a"), converted("CREATE TABLE a (id int)"));

        new DefaultObjectImporter(connection).importObjects(batch, ImportConfiguration.defaults());

        verify(connection, never()).setSchema(anyString());
    }

    @Test
    void appliesTheConfiguredStatementTimeout() throws SQLException {
        ConversionResults batch = new ConversionResults();
        add(batch, table("shop", "a"), converted("CREATE TABLE a (id int)"));

        new DefaultObjectImporter(connection)
                .importObjects(batch, ImportConfiguration.builder().statementTimeoutSeconds(30).build());

        verify(statement).setQueryTimeout(30);
    }

    private static void add(ConversionResults batch, ObjectId id, ConversionResult result) {
        batch.put(new DatabaseObject(id, "source ddl"), result);
    }

    private static ObjectId table(String schema, String table) {
        return ObjectId.table(schema, table);
    }

    private static ConversionResult converted(String... statements) {
        return result(ConversionStatus.SUCCEEDED, List.of(statements), "");
    }

    private static ConversionResult result(
            ConversionStatus status, List<String> statements, String error) {
        return ConversionResult.builder()
                .status(status)
                .statements(statements)
                .infos(List.of())
                .warnings(List.of())
                .error(error)
                .build();
    }
}
