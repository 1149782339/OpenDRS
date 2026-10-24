package io.opendrs.engine.replay.applier;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.engine.replay.ReplayConfig;
import io.opendrs.engine.replay.TestEvents;
import io.opendrs.engine.replay.connection.ConnectionProvider;
import io.opendrs.engine.replay.context.TaskContext;
import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.engine.replay.event.DataChangeEvent;
import io.opendrs.engine.replay.event.Operation;
import io.opendrs.engine.replay.event.TransactionEvent;
import io.opendrs.engine.replay.metrics.ReplayDirection;
import io.opendrs.engine.replay.metrics.ReplayMetrics;
import io.opendrs.engine.replay.naming.DefaultColumnNamingStrategy;
import io.opendrs.engine.replay.relation.FieldsMetaData;
import io.opendrs.common.metadata.TableId;
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
        ReplayConfig config = ReplayConfig.builder().connectionProvider(provider).build();
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

    @Test
    void countsRowsAndCommittedTransactions() throws Exception {
        Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:sink-applier-metrics;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
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
        RecordingReplayMetrics metrics = new RecordingReplayMetrics();
        ReplayConfig config = ReplayConfig.builder().connectionProvider(provider).metrics(metrics).build();
        JdbcApplier applier = new JdbcApplier(() -> true, config);
        applier.prepare(TaskContext.empty());
        // one source transaction: one row event + its END marker -> 1 row, 1 committed tx
        applier.apply(List.of(
                TestEvents.row(Operation.CREATE, "inventory", "customers", 1, "tx-1"),
                TestEvents.txMark("tx-1", TransactionEvent.Status.END)));
        assertThat(metrics.rows).isEqualTo(1);
        assertThat(metrics.txCount).isEqualTo(1);
        // a full-load table chunk has no END marker: rows still count, TPS does not
        applier.apply(List.of(TestEvents.row(Operation.CREATE, "inventory", "customers", 2, null)));
        assertThat(metrics.rows).isEqualTo(2);
        assertThat(metrics.txCount).isEqualTo(1);
        applier.release();
        connection.close();
    }

    private static final class RecordingReplayMetrics implements ReplayMetrics {
        private int rows;
        private int txCount;

        @Override
        public void appliedRows(ReplayDirection direction, long taskId, int rows) {
            this.rows += rows;
        }

        @Override
        public void txCommitted(ReplayDirection direction, long taskId, int txCount,
                double execMillis, double commitMillis) {
            this.txCount += txCount;
        }
    }
}
