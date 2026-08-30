package io.opendrs.sink.applier;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.sink.SinkConfig;
import io.opendrs.sink.connection.ConnectionProvider;
import io.opendrs.sink.context.TaskContext;
import io.opendrs.sink.dialect.DatabaseType;
import io.opendrs.sink.event.DataChangeEvent;
import io.opendrs.sink.event.Operation;
import io.opendrs.sink.naming.DefaultColumnNamingStrategy;
import io.opendrs.sink.relation.FieldsMetaData;
import io.opendrs.sink.relation.TableId;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.Test;

class JdbcApplierTest {

    @Test
    void insertsRowOnConflictReadyTable() throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:sink-applier;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
                "sa",
                "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS inventory");
            statement.execute(
                    "CREATE TABLE inventory.customers (id INT NOT NULL PRIMARY KEY, name VARCHAR(100) NOT NULL)");
        }
        ConnectionProvider provider = new ConnectionProvider() {
            @Override
            public Connection getConnection() {
                return connection;
            }

            @Override
            public void close() {
                // test owns the connection
            }
        };
        SinkConfig config = SinkConfig.builder().connectionProvider(provider).build();
        JdbcApplier applier = new JdbcApplier(() -> true, config);
        applier.prepare(TaskContext.empty());
        FieldsMetaData fields = new FieldsMetaData(
                List.of("id"), List.of("name"), Map.of("id", Schema.INT32_SCHEMA, "name", Schema.STRING_SCHEMA));
        DataChangeEvent event = DataChangeEvent.builder()
                .operation(Operation.CREATE)
                .tableId(new TableId("inventory", null, "customers"))
                .afterValues(Map.of("id", 42, "name", "cdc-row"))
                .beforeValues(Map.of())
                .fieldsMetaData(fields)
                .databaseType(DatabaseType.MYSQL)
                .topic("t")
                .offset(1L)
                .partition(0)
                .build();
        applier.apply(List.of(event));
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT id, name FROM inventory.customers WHERE id = 42")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("id")).isEqualTo(42);
            assertThat(rs.getString("name")).isEqualTo("cdc-row");
        }
        applier.release();
        connection.close();
    }
}
