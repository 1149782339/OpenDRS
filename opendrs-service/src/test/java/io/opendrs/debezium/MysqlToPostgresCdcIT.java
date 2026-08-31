package io.opendrs.debezium;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.debezium.DebeziumEngineConfig.EngineSpec;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import io.opendrs.migration.api.request.SchemaObject;
import io.opendrs.migration.api.request.TableSelection;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.domain.JobPhase;
import io.opendrs.migration.domain.MigrationMode;
import io.opendrs.migration.domain.MigrationTask;
import io.opendrs.migration.mapper.ConnectionInfoMapper;
import io.opendrs.migration.mapper.MigrationTaskMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * MySQL ROW binlog → PostgreSQL apply: one Engine snapshots existing rows then streams incremental
 * inserts through {@link SinkApplyChangeConsumer}.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfDockerAvailable
@Tag("it")
class MysqlToPostgresCdcIT {

    private static final Logger log = LoggerFactory.getLogger(MysqlToPostgresCdcIT.class);

    static {
        System.setProperty("testcontainers.ryuk.disabled", "true");
    }

    private static final String DB = "inventory";
    private static final String TABLE = "customers";
    private static final String CDC_USER = "cdc";
    private static final String CDC_PASSWORD = "cdc";
    private static final long MYSQL_SERVER_ID = 334455L;
    private static final long DEBEZIUM_SERVER_ID = 668802L;

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

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName(DB)
            .withUsername("pguser")
            .withPassword("pgpass")
            .withStartupTimeout(Duration.ofMinutes(2));

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConnectionInfoMapper connectionMapper;

    @Autowired
    private MigrationTaskMapper taskMapper;

    @Autowired
    private DebeziumCdcEngineFactory engineFactory;

    @BeforeEach
    void registerMetadataDataSource() {
        EngineDataSourceHolder.initialize(jdbcTemplate.getDataSource());
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void oneEngineSnapshotsExistingRowsThenAppliesIncrementalInsert() throws Exception {
        grantCdcPrivilegesAndCreateSourceTable();
        long taskId = insertTask();
        EngineSpec spec = new EngineSpec(
                taskId,
                sourceFromMysql(),
                List.of(new Table(new TableRef(DB, TABLE))),
                DEBEZIUM_SERVER_ID,
                targetFromPostgres(),
                new TableSelection(List.of(new SchemaObject(DB, List.of(TABLE), null, null)), null));

        ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "opendrs-pg-cdc-it");
            thread.setDaemon(true);
            return thread;
        });
        CdcEngine engine = engineFactory.create(spec);
        Future<?> engineDone = executor.submit(engine::run);
        try {
            boolean seedAppeared = awaitPostgresRow(1, "seed", 90, TimeUnit.SECONDS);
            assertThat(seedAppeared)
                    .as("snapshot rows that existed before Engine start must appear on PostgreSQL")
                    .isTrue();
            log.info("PG snapshot assertion: id=1 name=seed present");

            insertCustomer(99, "pg-cdc-row");
            boolean incrementalAppeared = awaitPostgresRow(99, "pg-cdc-row", 60, TimeUnit.SECONDS);
            assertThat(incrementalAppeared)
                    .as("insert after snapshot/stream must appear on PostgreSQL")
                    .isTrue();
            log.info("PG incremental assertion: id=99 name=pg-cdc-row present");
        } finally {
            engine.stop();
            engineDone.get(30, TimeUnit.SECONDS);
            executor.shutdownNow();
        }
    }

    private boolean awaitPostgresRow(int id, String name, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (System.nanoTime() < deadline) {
            try (Connection connection = postgresConnection();
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery(
                            "SELECT name FROM \"" + DB + "\".\"" + TABLE + "\" WHERE id = " + id)) {
                if (rs.next() && name.equals(rs.getString(1))) {
                    return true;
                }
            } catch (SQLException ex) {
                log.debug("waiting for postgres row: {}", ex.getMessage());
            }
            Thread.sleep(500);
        }
        return false;
    }

    private void grantCdcPrivilegesAndCreateSourceTable() throws SQLException {
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
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

    private Connection postgresConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private ConnectionInfo sourceFromMysql() {
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

    private ConnectionInfo targetFromPostgres() {
        ConnectionInfo info = new ConnectionInfo();
        info.setType(DbType.POSTGRESQL);
        info.setHost(POSTGRES.getHost());
        info.setPort(POSTGRES.getMappedPort(5432));
        info.setDbName(DB);
        info.setUsername(POSTGRES.getUsername());
        info.setPassword(POSTGRES.getPassword());
        return info;
    }

    private long insertTask() {
        ConnectionInfo source = connection("pg-cdc-it-src", DbType.MYSQL);
        ConnectionInfo target = connection("pg-cdc-it-tgt", DbType.POSTGRESQL);
        connectionMapper.insert(source);
        connectionMapper.insert(target);
        MigrationTask task = new MigrationTask();
        task.setName("pg-cdc-it-" + System.nanoTime());
        task.setMode(MigrationMode.FULL_AND_INCREMENTAL);
        task.setJobPhase(JobPhase.SCHEMA_SNAPSHOT);
        task.setSourceConnectionId(source.getId());
        task.setTargetConnectionId(target.getId());
        task.setTablesJson(new TableSelection(List.of(new SchemaObject(DB, List.of(TABLE), null, null)), null));
        taskMapper.insert(task);
        return task.getId();
    }

    private static ConnectionInfo connection(String name, DbType type) {
        ConnectionInfo info = new ConnectionInfo();
        info.setName(name);
        info.setType(type);
        info.setHost("localhost");
        info.setPort(type == DbType.POSTGRESQL ? 5432 : 3306);
        info.setDbName(DB);
        info.setUsername("u");
        info.setPassword("p");
        return info;
    }
}
