package io.opendrs.debezium;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.debezium.DebeziumEngineConfig.EngineSpec;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import io.opendrs.migration.api.request.SchemaObject;
import io.opendrs.migration.api.request.TableSelection;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.DebeziumOffset;
import io.opendrs.migration.domain.JobPhase;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.DebeziumOffsetMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Real MySQL binlog IT: SCHEMA_SNAPSHOT Engine exits after writing offset + schema history, then
 * INCREMENTAL Engine emits a CDC {@link SourceRecord} for a JDBC insert. Offset/history live in H2;
 * the source is Testcontainers MySQL (not docker-compose metadata).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfDockerAvailable
@Tag("it")
class MysqlBinlogCdcIT {

    private static final Logger log = LoggerFactory.getLogger(MysqlBinlogCdcIT.class);

    static {
        // Nested Docker often cannot run privileged Ryuk; the JUnit extension still stops @Container.
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    private static final String DB = "inventory";
    private static final String TABLE = "customers";
    private static final String CDC_USER = "cdc";
    private static final String CDC_PASSWORD = "cdc";
    private static final long MYSQL_SERVER_ID = 223344L;
    private static final long DEBEZIUM_SERVER_ID = 557441L;

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0")
            .withDatabaseName(DB)
            .withUsername(CDC_USER)
            .withPassword(CDC_PASSWORD)
            .withCommand(
                    "--server-id=" + MYSQL_SERVER_ID,
                    "--log-bin=mysql-bin",
                    "--binlog-format=ROW",
                    "--binlog-row-image=FULL",
                    "--gtid-mode=ON",
                    "--enforce-gtid-consistency=ON",
                    "--default-authentication-plugin=mysql_native_password")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConnectionInfoMapper connectionMapper;

    @Autowired
    private MigrationTaskMapper taskMapper;

    @Autowired
    private DebeziumOffsetMapper offsetMapper;

    @Autowired
    private DebeziumCdcEngineFactory engineFactory;

    @BeforeEach
    void registerMetadataDataSource() {
        EngineDataSourceHolder.initialize(jdbcTemplate.getDataSource());
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void schemaSnapshotExitsThenIncrementalEmitsInsert() throws Exception {
        grantCdcPrivilegesAndCreateTable();
        long taskId = insertTask();
        ConnectionInfo source = sourceFromContainer();
        EngineSpec spec = new EngineSpec(
                taskId, source, List.of(new Table(new TableRef(DB, TABLE))), DEBEZIUM_SERVER_ID);

        ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "opendrs-cdc-it");
            thread.setDaemon(true);
            return thread;
        });
        CdcEngine schemaEngine = engineFactory.createSchemaSnapshot(spec);
        try {
            Future<?> schemaDone = executor.submit(schemaEngine::run);
            schemaDone.get(90, TimeUnit.SECONDS);
            assertThat(schemaDone.isDone())
                    .as("SCHEMA_SNAPSHOT Engine must exit (SchemaOnlySnapshotter.shouldStream=false)")
                    .isTrue();
            assertThat(schemaDone.isCancelled()).isFalse();

            List<DebeziumOffset> offsets = offsetMapper.findByTaskId(taskId);
            assertThat(offsets).as("debezium_offset row for task " + taskId).isNotEmpty();
            String offsetVal = offsets.getFirst().getOffsetVal();
            assertThat(offsetVal)
                    .as("schema snapshot offset must include binlog file/pos")
                    .contains("file")
                    .contains("pos");
            log.info("SCHEMA_SNAPSHOT offset task={} val={}", taskId, offsetVal);

            Integer historyRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM debezium_schema_history WHERE task_id = ?", Integer.class, taskId);
            assertThat(historyRows)
                    .as("debezium_schema_history must have at least one record")
                    .isGreaterThanOrEqualTo(1);

            insertCustomer(42, "cdc-it-row");

            RecordingChangeConsumer consumer = new RecordingChangeConsumer(taskId);
            CdcEngine incremental = engineFactory.createIncremental(spec, consumer);
            Future<?> incrementalDone = executor.submit(incremental::run);
            try {
                boolean seen = consumer.awaitDataChange(60, TimeUnit.SECONDS);
                assertThat(seen)
                        .as("INCREMENTAL Engine must emit a CDC SourceRecord after INSERT")
                        .isTrue();

                SourceRecord record = consumer.firstTableDataChange();
                assertThat(record).isNotNull();
                Object op = LoggingChangeConsumer.extractOp(record);
                Struct after = RecordingChangeConsumer.after(record);
                String assertionLine = "CDC assertion: op="
                        + op
                        + " sourceOffset="
                        + record.sourceOffset()
                        + " after="
                        + after;
                log.info(assertionLine);
                System.out.println(assertionLine);

                assertThat(op)
                        .as(assertionLine)
                        .isIn("c", "r");
                assertThat(after).as("envelope after for insert").isNotNull();
                assertThat(String.valueOf(after.get("name"))).isEqualTo("cdc-it-row");
                assertThat(String.valueOf(after.get("id"))).isEqualTo("42");
                assertThat(record.sourceOffset()).isNotEmpty();
            } finally {
                incremental.stop();
                incrementalDone.get(30, TimeUnit.SECONDS);
            }
        } finally {
            schemaEngine.stop();
            executor.shutdownNow();
        }
    }

    private void grantCdcPrivilegesAndCreateTable() throws SQLException {
        String jdbcUrl = MYSQL.getJdbcUrl();
        try (Connection root = DriverManager.getConnection(jdbcUrl, "root", MYSQL.getPassword());
                Statement statement = root.createStatement()) {
            statement.execute(
                    "ALTER USER '"
                            + CDC_USER
                            + "'@'%' IDENTIFIED WITH mysql_native_password BY '"
                            + CDC_PASSWORD
                            + "'");
            statement.execute(
                    "GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT, "
                            + "LOCK TABLES ON *.* TO '"
                            + CDC_USER
                            + "'@'%'");
            statement.execute("FLUSH PRIVILEGES");
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS "
                            + DB
                            + "."
                            + TABLE
                            + " (id INT NOT NULL PRIMARY KEY, name VARCHAR(100) NOT NULL) ENGINE=InnoDB");
            statement.execute("INSERT INTO " + DB + "." + TABLE + " (id, name) VALUES (1, 'seed')");
        }
    }

    private void insertCustomer(int id, String name) throws SQLException {
        try (Connection connection = MYSQL.createConnection("");
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO " + DB + "." + TABLE + " (id, name) VALUES (" + id + ", '" + name + "')");
        }
    }

    private ConnectionInfo sourceFromContainer() {
        ConnectionInfo info = new ConnectionInfo();
        info.setType(DbType.MYSQL);
        info.setHost(MYSQL.getHost());
        info.setPort(MYSQL.getMappedPort(3306));
        info.setDbName(DB);
        info.setUsername(CDC_USER);
        info.setPassword(CDC_PASSWORD);
        info.setExtra(Map.of("useSsl", false));
        return info;
    }

    private long insertTask() {
        ConnectionInfo source = connection("cdc-it-src");
        ConnectionInfo target = connection("cdc-it-tgt");
        connectionMapper.insert(source);
        connectionMapper.insert(target);
        MigrationTask task = new MigrationTask();
        task.setName("cdc-it-" + System.nanoTime());
        task.setMode(MigrationMode.FULL_AND_INCREMENTAL);
        task.setJobPhase(JobPhase.SCHEMA_SNAPSHOT);
        task.setSourceConnectionId(source.getId());
        task.setTargetConnectionId(target.getId());
        task.setTablesJson(new TableSelection(
                List.of(new SchemaObject(DB, List.of(TABLE), null, null)), null));
        taskMapper.insert(task);
        return task.getId();
    }

    private static ConnectionInfo connection(String name) {
        ConnectionInfo info = new ConnectionInfo();
        info.setName(name);
        info.setType(DbType.MYSQL);
        info.setHost("localhost");
        info.setPort(3306);
        info.setDbName(DB);
        info.setUsername(CDC_USER);
        info.setPassword(CDC_PASSWORD);
        return info;
    }
}
