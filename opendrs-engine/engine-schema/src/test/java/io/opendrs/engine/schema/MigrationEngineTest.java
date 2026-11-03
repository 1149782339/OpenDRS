package io.opendrs.engine.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.common.metadata.TableId;
import io.opendrs.common.selection.TableSelection;
import io.opendrs.engine.schema.convert.ConversionResult;
import io.opendrs.engine.schema.convert.ConversionResults;
import io.opendrs.engine.schema.convert.ConversionStatus;
import io.opendrs.engine.schema.export.ExportConfiguration;
import io.opendrs.engine.schema.export.ExportResults;
import io.opendrs.engine.schema.imports.ImportConfiguration;
import io.opendrs.engine.schema.imports.ImportResults;
import io.opendrs.engine.schema.meta.DatabaseObject;
import io.opendrs.engine.schema.meta.ObjectId;
import io.opendrs.engine.schema.meta.ObjectType;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionConfig;
import io.opendrs.jdbc.JdbcConnectionFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MigrationEngineTest {

    private JdbcConnectionFactory factory;
    private JdbcConnection jdbcConnection;
    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException {
        factory = mock(JdbcConnectionFactory.class);
        jdbcConnection = mock(JdbcConnection.class);
        connection = mock(Connection.class);
        when(factory.open(any())).thenReturn(jdbcConnection);
        when(jdbcConnection.unwrap()).thenReturn(connection);
    }

    @Test
    void rejectsSourceTypeWithoutSchemaDialectBeforeOpeningAConnection() {
        MigrationEngine engine = new MigrationEngine(factory);

        assertThatThrownBy(() -> engine.exportObjects(
                        config(DatabaseType.ORACLE), selectionConfiguration()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ORACLE");
        verify(factory, never()).open(any());
    }

    @Test
    void exportsThroughItsOwnConnectionAndRecordsTheSourceType() throws SQLException {
        PreparedStatement prepared = mock(PreparedStatement.class);
        ResultSet preparedRows = mock(ResultSet.class);
        Statement statement = mock(Statement.class);
        ResultSet ddlRow = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(prepared);
        when(prepared.executeQuery()).thenReturn(preparedRows);
        when(preparedRows.next()).thenReturn(true, false);
        when(preparedRows.getString(anyInt())).thenReturn("customers");
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(ddlRow);
        when(ddlRow.next()).thenReturn(true);
        when(ddlRow.getString(2)).thenReturn("CREATE TABLE `shop`.`customers` (`id` int)");

        ExportResults results = new MigrationEngine(factory)
                .exportObjects(config(DatabaseType.MYSQL), selectionConfiguration());

        assertThat(results.getTables()).extracting(object -> object.id().tableId().getTable())
                .containsExactly("customers");
        assertThat(results.getDatabaseType()).isEqualTo(DatabaseType.MYSQL);
        verify(jdbcConnection).close();
    }

    @Test
    void importsThroughItsOwnConnectionApplyingTheSchemaStrategy() throws SQLException {
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        ConversionResults batch = new ConversionResults();
        batch.put(
                new DatabaseObject(
                        new ObjectId(TableId.of("shop", "customers"), ObjectType.TABLE),
                        "CREATE TABLE `shop`.`customers` (`id` int)"),
                ConversionResult.builder()
                        .status(ConversionStatus.SUCCEEDED)
                        .statements(List.of("CREATE TABLE \"customers\" (\"id\" int)"))
                        .infos(List.of())
                        .warnings(List.of())
                        .error("")
                        .build());

        ImportResults results = new MigrationEngine(factory).importObjects(
                config(DatabaseType.POSTGRESQL),
                batch,
                ImportConfiguration.builder()
                        .schemaNamingStrategy(schema -> "pg_" + schema)
                        .build());

        assertThat(results.size()).isEqualTo(1);
        verify(connection).setSchema("pg_shop");
        verify(jdbcConnection).close();
    }

    private static ExportConfiguration selectionConfiguration() {
        return new ExportConfiguration.Builder()
                .selection(TableSelection.builder().includeSchema("shop").build())
                .build();
    }

    private static JdbcConnectionConfig config(DatabaseType type) {
        return JdbcConnectionConfig.builder()
                .type(type)
                .host("127.0.0.1")
                .port(3306)
                .database("shop")
                .username("u")
                .password("p")
                .build();
    }
}
