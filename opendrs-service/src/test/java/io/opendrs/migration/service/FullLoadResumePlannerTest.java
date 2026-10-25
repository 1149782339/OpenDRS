package io.opendrs.migration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The resume decision: done lookup, remaining computation, the exact skip log line, and the
 * dialect-aware TRUNCATE rendering. Everything is mocked but the planner itself and the real
 * {@link MappingValidator} name-mapping chain.
 */
class FullLoadResumePlannerTest {

    private FullLoadCheckpointMapper checkpointMapper;
    private JdbcConnectionFactory connectionFactory;
    private FullLoadResumePlanner planner;

    private JdbcConnection targetConnection;
    private List<String> executedSql;

    private Logger plannerLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() throws Exception {
        checkpointMapper = mock(FullLoadCheckpointMapper.class);
        connectionFactory = mock(JdbcConnectionFactory.class);
        planner = new FullLoadResumePlanner(checkpointMapper, new MappingValidator(), connectionFactory);

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

        plannerLogger = (Logger) LoggerFactory.getLogger(FullLoadResumePlanner.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        plannerLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        plannerLogger.detachAppender(logAppender);
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
    void noCheckpointsMeansNoResume() {
        when(checkpointMapper.findDoneTables(7L, "forward")).thenReturn(Set.of());

        assertThat(planner.resumeTables(7L, "forward", null, mysql("hr"), mysql("hr")))
                .isNull();
        verifyNoInteractions(connectionFactory);
        assertThat(skipLogLines()).isEmpty();
    }

    @Test
    void doneTablesAreSkippedAndRemainingTruncatedWithTargetQuoting() {
        when(checkpointMapper.findDoneTables(7L, "forward"))
                .thenReturn(Set.of("hr.emp"));
        when(checkpointMapper.findAllTables(7L, "forward"))
                .thenReturn(List.of("hr.dept", "hr.emp"));

        Set<String> remaining =
                planner.resumeTables(7L, "forward", null, mysql("hr"), mysql("hr"));

        assertThat(remaining).containsExactly("hr.dept");
        // MySQL target: backtick-quoted schema.table, one statement per remaining table.
        assertThat(executedSql).containsExactly("truncate table `hr`.`dept`");
        assertThat(skipLogLines()).containsExactly("table \"hr.emp\" 已经完成，skip to do full snapshot");
    }

    @Test
    void sourceNamesMapThroughTheMappingChainForTruncate() throws Exception {
        // PG target: double-quoted, and the schema mapping renames db "hr" to "public".
        org.mockito.Mockito.when(targetConnection.unwrap().getMetaData().getIdentifierQuoteString())
                .thenReturn("\"");
        when(checkpointMapper.findDoneTables(7L, "forward")).thenReturn(Set.of("hr.emp"));
        when(checkpointMapper.findAllTables(7L, "forward")).thenReturn(List.of("hr.dept"));
        TableSelection selection = new TableSelection(
                List.of(), List.of(), List.of(), List.of(),
                new TableMappings(List.of(new SchemaMapping("hr", "public")), null));

        Set<String> remaining =
                planner.resumeTables(7L, "forward", selection, mysql("hr"), postgres("public"));

        assertThat(remaining).containsExactly("hr.dept");
        assertThat(executedSql).containsExactly("truncate table \"public\".\"dept\"");
    }

    @Test
    void allDoneResumesNothingAndTruncatesNothing() {
        // LinkedHashSet keeps the stub's iteration order, which drives the skip log order.
        when(checkpointMapper.findDoneTables(7L, "forward"))
                .thenReturn(new LinkedHashSet<>(List.of("hr.dept", "hr.emp")));
        when(checkpointMapper.findAllTables(7L, "forward"))
                .thenReturn(List.of("hr.dept", "hr.emp"));

        assertThat(planner.resumeTables(7L, "forward", null, mysql("hr"), mysql("hr")))
                .isEmpty();
        assertThat(executedSql).isEmpty();
        assertThat(skipLogLines())
                .containsExactly(
                        "table \"hr.dept\" 已经完成，skip to do full snapshot",
                        "table \"hr.emp\" 已经完成，skip to do full snapshot");
    }

    @Test
    void missingListFallsBackToSelectionAndGivesUpWhenExpansionFails() {
        when(checkpointMapper.findDoneTables(7L, "forward")).thenReturn(Set.of("hr.emp"));
        when(checkpointMapper.findAllTables(7L, "forward")).thenReturn(List.of());
        // The source is unreachable, so the selection cannot expand either.
        doThrow(new io.opendrs.jdbc.JdbcException("source down"))
                .when(connectionFactory).open(any());

        assertThat(planner.resumeTables(7L, "forward", null, mysql("hr"), mysql("hr")))
                .isNull();
    }

    @Test
    void truncateFailureDegradesToTheUpsertFallback() {
        when(checkpointMapper.findDoneTables(7L, "forward")).thenReturn(Set.of("hr.emp"));
        when(checkpointMapper.findAllTables(7L, "forward")).thenReturn(List.of("hr.dept"));
        doThrow(new RuntimeException("access denied")).when(targetConnection).execute(anyString());

        // The resume itself still stands — re-exported rows are digested by dup-key → upsert.
        assertThat(planner.resumeTables(7L, "forward", null, mysql("hr"), mysql("hr")))
                .containsExactly("hr.dept");
    }

    private List<String> skipLogLines() {
        return logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("已经完成，skip to do full snapshot"))
                .toList();
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
