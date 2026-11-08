package io.opendrs.engine.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opendrs.common.log.MigrationLogListener;
import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.common.metadata.TableId;
import io.opendrs.common.selection.SchemaMapping;
import io.opendrs.common.selection.TableMappings;
import io.opendrs.common.selection.TableSelection;
import io.opendrs.engine.replay.dialect.DatabaseDialect;
import io.opendrs.engine.replay.mapping.MappingValidator;
import io.opendrs.engine.schema.SchemaMigrationException;
import io.opendrs.engine.schema.convert.ConversionConfiguration;
import io.opendrs.engine.schema.convert.ConversionResult;
import io.opendrs.engine.schema.convert.ConversionResults;
import io.opendrs.engine.schema.convert.ConversionStatus;
import io.opendrs.engine.schema.export.ExportConfiguration;
import io.opendrs.engine.schema.export.ExportResults;
import io.opendrs.engine.schema.imports.ImportConfiguration;
import io.opendrs.engine.schema.imports.ImportResults;
import io.opendrs.engine.schema.meta.DatabaseObject;
import io.opendrs.engine.schema.meta.ObjectId;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.JdbcConnectionFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SchemaMigrationTest {

    private static final JdbcConnectionConfig SOURCE = config(DatabaseType.MYSQL);
    private static final JdbcConnectionConfig TARGET = config(DatabaseType.POSTGRESQL);

    private io.opendrs.engine.schema.MigrationEngine schemaEngine;
    private DatabaseDialect dialect;
    private Connection connection;
    private SchemaMigration migration;

    @BeforeEach
    void setUp() throws SQLException {
        JdbcConnectionFactory factory = mock(JdbcConnectionFactory.class);
        JdbcConnection jdbcConnection = mock(JdbcConnection.class);
        connection = mock(Connection.class);
        schemaEngine = mock(io.opendrs.engine.schema.MigrationEngine.class);
        dialect = mock(DatabaseDialect.class);
        when(factory.open(any())).thenReturn(jdbcConnection);
        when(jdbcConnection.connection()).thenReturn(connection);
        migration = new SchemaMigration(
                factory, schemaEngine, new MappingValidator(), (target, tableNaming, columnNaming) -> dialect);
    }

    @Test
    void skipsObjectsThatAlreadyExistOnTheTargetAndImportsTheRest() throws Exception {
        ObjectId present = object("customers");
        ObjectId absent = object("orders");
        givenConverted(present, absent);
        when(dialect.tableExists(connection, tableId("customers"))).thenReturn(true);
        when(dialect.tableExists(connection, tableId("orders"))).thenReturn(false);
        // The importer only ever sees what was not skipped.
        givenImported(absent, ConversionStatus.SUCCEEDED);

        SchemaMigrationOutcome outcome = migration.run(
                SOURCE, TARGET, selection(), tables("customers", "orders"), MigrationLogListener.NONE);

        assertThat(outcome.skipped()).extracting(table -> table.getTable())
                .containsExactly("customers");
        assertThat(outcome.created()).extracting(table -> table.getTable())
                .containsExactly("orders");
        ArgumentCaptor<ConversionResults> pending = ArgumentCaptor.forClass(ConversionResults.class);
        verify(schemaEngine).importObjects(eq(TARGET), pending.capture(), any());
        assertThat(pending.getValue().entries()).extracting(entry -> entry.getKey().id())
                .containsExactly(absent);
    }

    @Test
    void failsTheRunWhenTheTargetRejectsAnObject() throws Exception {
        ObjectId broken = object("broken");
        givenConverted(broken);
        when(dialect.tableExists(connection, tableId("broken"))).thenReturn(false);
        givenImported(broken, ConversionStatus.FAILED, "42P07: relation already exists");

        assertThatThrownBy(() -> migration.run(
                        SOURCE, TARGET, selection(), tables("broken"), MigrationLogListener.NONE))
                .isInstanceOf(SchemaMigrationException.class)
                .hasMessageContaining("1 of 1 object(s) could not be created")
                .hasMessageContaining("42P07");
    }

    @Test
    void createsNothingWhenTheExportReportedErrors() throws Exception {
        ExportResults exported = new ExportResults();
        exported.getMessage().addErrorMessage("dependency shop.customers was not exported");
        when(schemaEngine.exportObjects(any(), any())).thenReturn(exported);

        assertThatThrownBy(() -> migration.run(
                        SOURCE, TARGET, selection(), tables("orders"), MigrationLogListener.NONE))
                .isInstanceOf(SchemaMigrationException.class)
                .hasMessageContaining("source export is incomplete");
        verify(schemaEngine, never()).importObjects(any(), any(), any());
        verify(schemaEngine, never()).convert(any(), any());
    }

    @Test
    void handsTheSchemaMappingToTheImporter() throws Exception {
        ObjectId customers = object("customers");
        givenConverted(customers);
        when(dialect.tableExists(connection, tableId("customers"))).thenReturn(false);
        givenImported(customers, ConversionStatus.SUCCEEDED);
        TableSelection mapped = TableSelection.builder()
                .includeSchema("shop")
                .mappings(new TableMappings(List.of(new SchemaMapping("shop", "pg_shop")), null))
                .build();

        migration.run(SOURCE, TARGET, mapped, tables("customers"), MigrationLogListener.NONE);

        ArgumentCaptor<ImportConfiguration> configuration =
                ArgumentCaptor.forClass(ImportConfiguration.class);
        verify(schemaEngine).importObjects(eq(TARGET), any(), configuration.capture());
        assertThat(configuration.getValue().getSchemaNamingStrategy().resolveSchemaName("shop"))
                .isEqualTo("pg_shop");
        assertThat(configuration.getValue().getSchemaNamingStrategy().resolveSchemaName("other"))
                .isEqualTo("other");
    }

    @Test
    void listsOnlyTheTargetsThatAreMissing() throws Exception {
        when(dialect.tableExists(connection, tableId("customers"))).thenReturn(true);
        when(dialect.tableExists(connection, tableId("orders"))).thenReturn(false);

        List<TableId> missing =
                migration.missingTargets(TARGET, tables("customers", "orders"), selection());

        assertThat(missing).extracting(table -> table.getTable()).containsExactly("orders");
    }

    @Test
    void ensureTablesAsksForThePrimaryKeyAndNothingElse() throws Exception {
        ObjectId customers = object("customers");
        givenConverted(customers);
        when(dialect.tableExists(connection, tableId("customers"))).thenReturn(false);
        givenImported(customers, ConversionStatus.SUCCEEDED);

        migration.ensureTables(
                SOURCE, TARGET, selection(), tables("customers"), MigrationLogListener.NONE);

        ArgumentCaptor<ExportConfiguration> exported =
                ArgumentCaptor.forClass(ExportConfiguration.class);
        verify(schemaEngine).exportObjects(eq(SOURCE), exported.capture());
        assertThat(exported.getValue().includePrimaryKey()).isTrue();
        assertThat(exported.getValue().includeUniqueKeys()).isFalse();
        assertThat(exported.getValue().includeSecondaryIndexes()).isFalse();
        assertThat(exported.getValue().includeForeignKeys()).isFalse();

        ArgumentCaptor<ConversionConfiguration> converted =
                ArgumentCaptor.forClass(ConversionConfiguration.class);
        verify(schemaEngine).convert(any(), converted.capture());
        assertThat(converted.getValue().includePrimaryKey()).isTrue();
        assertThat(converted.getValue().includeUniqueKeys()).isFalse();
        assertThat(converted.getValue().includeSecondaryIndexes()).isFalse();
        assertThat(converted.getValue().includeForeignKeys()).isFalse();
        assertThat(converted.getValue().ifNotExists()).isTrue();
    }

    private void givenConverted(ObjectId... ids) throws SQLException {
        ConversionResults converted = new ConversionResults();
        for (ObjectId id : ids) {
            converted.put(
                    new DatabaseObject(id, "CREATE TABLE `shop`.`" + id.tableId().getTable() + "` (`id` int)"),
                    succeeded());
        }
        when(schemaEngine.exportObjects(any(), any())).thenReturn(new ExportResults());
        when(schemaEngine.convert(any(), any())).thenReturn(converted);
    }

    private void givenImported(ObjectId id, ConversionStatus status) throws SQLException {
        givenImported(id, status, "");
    }

    private void givenImported(ObjectId id, ConversionStatus status, String error) throws SQLException {
        ImportResults results = new ImportResults();
        results.put(id, ConversionResult.builder()
                .status(status)
                .statements(List.of("CREATE TABLE t (id int)"))
                .infos(List.of())
                .warnings(List.of())
                .error(error)
                .build());
        when(schemaEngine.importObjects(eq(TARGET), any(), any())).thenReturn(results);
    }

    private static TableSelection selection() {
        return TableSelection.builder().includeSchema("shop").build();
    }

    private static List<TableId> tables(String... names) {
        return java.util.Arrays.stream(names)
                .map(name -> TableId.of("shop", name))
                .toList();
    }

    private static ObjectId object(String name) {
        return ObjectId.table("shop", name);
    }

    private static TableId tableId(String name) {
        return TableId.of("shop", name);
    }

    private static ConversionResult succeeded() {
        return ConversionResult.builder()
                .status(ConversionStatus.SUCCEEDED)
                .statements(List.of("CREATE TABLE t (id int)"))
                .infos(List.of())
                .warnings(List.of())
                .error("")
                .build();
    }

    private static JdbcConnectionConfig config(DatabaseType type) {
        return JdbcConnectionConfig.builder()
                .type(type)
                .host("127.0.0.1")
                .port(5432)
                .database("shop")
                .username("u")
                .password("p")
                .build();
    }
}
