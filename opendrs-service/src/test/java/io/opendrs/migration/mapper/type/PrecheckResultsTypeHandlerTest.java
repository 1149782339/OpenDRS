package io.opendrs.migration.mapper.type;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opendrs.engine.precheck.CheckGroup;
import io.opendrs.engine.precheck.CheckIds;
import io.opendrs.engine.precheck.CheckMeta;
import io.opendrs.engine.precheck.Evaluation;
import io.opendrs.engine.precheck.PrecheckItem;
import io.opendrs.engine.precheck.PrecheckResults;
import io.opendrs.engine.precheck.Severity;
import io.opendrs.engine.precheck.Side;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The checklist lives in one JSON column, so the column's shape is a compatibility surface: rows
 * written by the previous release must still load, and today's shape must survive a round trip.
 */
class PrecheckResultsTypeHandlerTest {

    private final PrecheckResultsTypeHandler handler = new PrecheckResultsTypeHandler();

    @Test
    void legacyShapeLoadsAsAnEmptyChecklistInsteadOfFailing() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("precheck_results_json")).thenReturn("""
                {"source":[{"ok":true,"name":"log_bin","message":"log_bin is ON","table":null}],
                 "target":[{"ok":true,"name":"table_absent","message":"absent","table":null}],
                 "running":null}
                """);

        PrecheckResults results = handler.getNullableResult(rs, "precheck_results_json");

        assertThat(results.items()).isEmpty();
        assertThat(results.profileId()).isNull();
        assertThat(results.runningId()).isNull();
        // An unreadable old run must not be mistaken for a pass: the task asks to be re-checked.
        assertThat(results.admitted()).isFalse();
    }

    @Test
    void currentShapeSurvivesARoundTrip() throws Exception {
        PrecheckResults original = PrecheckResults.planned("mysql-to-postgresql", List.of(
                PrecheckItem.pending(
                                new CheckMeta(CheckIds.SOURCE_PRIMARY_KEY, "源库无主键表检查",
                                        CheckGroup.OBJECT, Side.SOURCE),
                                Severity.WARNING, 0)
                        .completed(Evaluation.fail("2 张表无主键", List.of("hr.log", "hr.audit"), "add keys", 10))))
                .withRunning(CheckIds.SOURCE_PRIMARY_KEY);

        PreparedStatement statement = mock(PreparedStatement.class);
        handler.setNonNullParameter(statement, 1, original, null);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(statement).setString(eq(1), json.capture());

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(anyInt())).thenReturn(json.getValue());
        PrecheckResults loaded = handler.getNullableResult(rs, 1);

        assertThat(loaded.profileId()).isEqualTo("mysql-to-postgresql");
        assertThat(loaded.runningId()).isEqualTo(CheckIds.SOURCE_PRIMARY_KEY);
        PrecheckItem item = loaded.items().getFirst();
        assertThat(item.id()).isEqualTo(CheckIds.SOURCE_PRIMARY_KEY);
        assertThat(item.group()).isEqualTo(CheckGroup.OBJECT);
        assertThat(item.side()).isEqualTo(Side.SOURCE);
        assertThat(item.severity()).isEqualTo(Severity.WARNING);
        assertThat(item.verdict()).isEqualTo(io.opendrs.engine.precheck.Verdict.FAIL);
        assertThat(item.message()).isEqualTo("2 张表无主键");
        assertThat(item.details()).containsExactly("hr.log", "hr.audit");
        assertThat(item.advice()).isEqualTo("add keys");
    }
}
