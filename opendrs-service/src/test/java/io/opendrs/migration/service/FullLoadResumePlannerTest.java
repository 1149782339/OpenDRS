package io.opendrs.migration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opendrs.common.log.MigrationLogLevel;
import io.opendrs.common.log.MigrationLogListener;
import io.opendrs.common.metadata.TableId;
import io.opendrs.engine.replay.mapping.MappingValidator;
import io.opendrs.engine.replay.mapping.SchemaMapping;
import io.opendrs.engine.replay.mapping.TableMappings;
import io.opendrs.engine.replay.mapping.TableSelection;
import io.opendrs.jdbc.JdbcConnection;
import io.opendrs.jdbc.JdbcConnectionFactory;
import io.opendrs.migration.domain.ConnectionInfo;
import io.opendrs.migration.domain.DbType;
import io.opendrs.migration.mapper.FullLoadCheckpointMapper;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The resume decision against the presence=done store: completed lookup by pipeline, remaining =
 * current selection − completed, the exact skip/truncate narration, and the dialect-aware
 * TRUNCATE rendering. Everything is mocked but the planner itself and the real
 * {@link MappingValidator} name-mapping chain. Table-level selections expand verbatim, so no
 * source connection is involved unless a test says so.
 */
class FullLoadResumePlannerTest {

    private static final long TASK_ID = 7L;
    private static final long PIPELINE_ID = 41L;

    private FullLoadCheckpointMapper checkpointMapper;
    private JdbcConnectionFactory connectionFactory;
    private MigrationLogService logService;
    private FullLoadResumePlanner planner;

    private JdbcConnection targetConnection;
    private List<String> executedSql;
    private List<String> emitted;

    @BeforeEach
    void setUp() throws Exception {
        checkpointMapper = mock(FullLoadCheckpointMapper.class);
        connectionFactory = mock(JdbcConnectionFactory.class);
        logService = mock(MigrationLogService.class);
        emitted = new ArrayList<>();
        when(logService.listenerFor(anyLong())).thenAnswer(invocation ->
                (MigrationLogListener) (level, message) -> emitted.add(level + ":" + message));
        planner = new FullLoadResumePlanner(checkpointMapper, new MappingValidator(),
                connectionFactory, logService);

        targetConnection = mock(JdbcConnection.class);
        executedSql = new ArrayList<>();
        doAnswer(invocation -> {
            executedSql.add(invocation.getArgument(0));
            return null;
        }).when(targetConnection).execute(anyString());
        java.sql.Connection raw = mock(java.sql.Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(raw.getMetaData()).thenReturn(metaData);
        when(metaData.getIdentifierQuoteString()).thenReturn("`");
        when(targetConnection.unwrap()).thenReturn(raw);
        when(connectionFactory.open(any())).thenReturn(targetConnection);
    }

    @Test
    void remainingKeepsListOrderAndDropsDone() {
        List<String> all = List.of("hr.b", "hr.a", "hr.c");
        Set<String> done = new LinkedHashSet<>(List.of("hr.a"));

        assertThat(FullLoadResumePlanner.remaining(all, done)).containsExactly("hr.b", "hr.c");
        // A done entry that is not in the list changes nothing.
        assertThat(FullLoadResumePlanner.remaining(all, Set.of("hr.a", "hr.zzz")))
                .containsExactly("hr.b", "hr.c");
    }

    @Test
    void noCompletedTablesMeansNoResume() {
        when(checkpointMapper.findDoneTables(PIPELINE_ID)).thenReturn(Set.of());

        assertThat(planner.resumeTables(TASK_ID, PIPELINE_ID, null, mysql("hr"), mysql("hr")))
                .isNull();
        // Not even the source-side expansion runs for a fresh pipeline.
        verifyNoInteractions(connectionFactory);
        assertThat(emitted).isEmpty();
    }

    @Test
    void doneTablesAreSkippedAndRemainingTruncatedWithTargetQuoting() {
        when(checkpointMapper.findDoneTables(PIPELINE_ID)).thenReturn(Set.of("hr.emp"));

        Set<String> remaining = planner.resumeTables(
                TASK_ID, PIPELINE_ID, selectionOf("hr.dept", "hr.emp"), mysql("hr"), mysql("hr"));

        assertThat(remaining).containsExactly("hr.dept");
        // MySQL target: backtick-quoted schema.table, one statement per remaining table.
        assertThat(executedSql).containsExactly("truncate table `hr`.`dept`");
        assertThat(emitted).containsExactly(
                "INFO:table \"hr.emp\" already done, skipped",
                "INFO:truncate target table `hr`.`dept` for resume");
    }

    @Test
    void sourceNamesMapThroughTheMappingChainForTruncate() throws Exception {
        // PG target: double-quoted, and the schema mapping renames db "hr" to "public".
        org.mockito.Mockito.when(targetConnection.unwrap().getMetaData().getIdentifierQuoteString())
                .thenReturn("\"");
        when(checkpointMapper.findDoneTables(PIPELINE_ID)).thenReturn(Set.of("hr.emp"));
        TableSelection selection = new TableSelection(
                List.of(), List.of(TableId.of("hr", "dept")), List.of(), List.of(),
                new TableMappings(List.of(new SchemaMapping("hr", "public")), null));

        Set<String> remaining =
                planner.resumeTables(TASK_ID, PIPELINE_ID, selection, mysql("hr"), postgres("public"));

        assertThat(remaining).containsExactly("hr.dept");
        assertThat(executedSql).containsExactly("truncate table \"public\".\"dept\"");
    }

    @Test
    void allDoneResumesNothingAndTruncatesNothing() {
        // LinkedHashSet keeps the stub's iteration order, which drives the skip log order.
        when(checkpointMapper.findDoneTables(PIPELINE_ID))
                .thenReturn(new LinkedHashSet<>(List.of("hr.dept", "hr.emp")));

        assertThat(planner.resumeTables(
                TASK_ID, PIPELINE_ID, selectionOf("hr.dept", "hr.emp"), mysql("hr"), mysql("hr")))
                .isEmpty();
        assertThat(executedSql).isEmpty();
        assertThat(emitted).containsExactly(
                "INFO:table \"hr.dept\" already done, skipped",
                "INFO:table \"hr.emp\" already done, skipped");
    }

    @Test
    void expansionFailureFallsBackToTheNormalFullLoad() {
        when(checkpointMapper.findDoneTables(PIPELINE_ID)).thenReturn(Set.of("hr.emp"));
        // A whole-instance selection needs a live source listing; the source is unreachable.
        doThrow(new io.opendrs.jdbc.JdbcException("source down"))
                .when(connectionFactory).open(any());

        assertThat(planner.resumeTables(TASK_ID, PIPELINE_ID, null, mysql("hr"), mysql("hr")))
                .isNull();
        assertThat(emitted).anyMatch(m -> m.startsWith("WARNING:could not expand"));
    }

    @Test
    void truncateFailureDegradesToTheUpsertFallback() {
        when(checkpointMapper.findDoneTables(PIPELINE_ID)).thenReturn(Set.of("hr.emp"));
        doThrow(new RuntimeException("access denied")).when(targetConnection).execute(anyString());

        // The resume itself still stands — re-exported rows are digested by dup-key → upsert.
        assertThat(planner.resumeTables(
                TASK_ID, PIPELINE_ID, selectionOf("hr.dept"), mysql("hr"), mysql("hr")))
                .containsExactly("hr.dept");
        assertThat(emitted).anyMatch(m -> m.startsWith("WARNING:truncate failed"));
    }

    private static TableSelection selectionOf(String... dbTables) {
        List<TableId> tables = new ArrayList<>();
        for (String dbTable : dbTables) {
            int dot = dbTable.indexOf('.');
            tables.add(TableId.of(dbTable.substring(0, dot), dbTable.substring(dot + 1)));
        }
        return new TableSelection(List.of(), tables, List.of(), List.of(), null);
    }

    private static ConnectionInfo mysql(String db) {
        return connection(DbType.MYSQL, db);
    }

    private static ConnectionInfo postgres(String db) {
        return connection(DbType.POSTGRESQL, db);
    }

    private static ConnectionInfo connection(DbType type, String db) {
        ConnectionInfo info = new ConnectionInfo();
        info.setType(type);
        info.setHost("localhost");
        info.setPort(3306);
        info.setDbName(db);
        info.setUsername("u");
        info.setPassword("p");
        return info;
    }
}
