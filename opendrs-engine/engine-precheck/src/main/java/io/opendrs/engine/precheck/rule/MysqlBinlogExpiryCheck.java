package io.opendrs.engine.precheck.rule;

import io.opendrs.engine.precheck.CheckGroup;
import io.opendrs.engine.precheck.CheckIds;
import io.opendrs.engine.precheck.CheckMeta;
import io.opendrs.engine.precheck.CheckRule;
import io.opendrs.engine.precheck.Evaluation;
import io.opendrs.engine.precheck.Finding;
import io.opendrs.engine.precheck.PrecheckContext;
import io.opendrs.engine.precheck.Side;
import io.opendrs.engine.precheck.Verdict;
import io.opendrs.engine.precheck.fact.FactStore;
import java.util.List;
import java.util.Map;

/**
 * Binlog retention. MySQL 8.0 deprecated {@code expire_logs_days} in favour of
 * {@code binlog_expire_logs_seconds} and 8.4 removed it outright, so the seconds parameter wins when
 * it exists and the days parameter is only a fallback for older servers.
 *
 * <p>Retention has to outlive the snapshot: if the binlog is purged while the full dump runs, the
 * incremental phase can never catch up.
 */
public final class MysqlBinlogExpiryCheck implements CheckRule {

    private static final long ONE_DAY_SECONDS = 86_400L;

    @Override
    public CheckMeta meta() {
        return new CheckMeta(
                CheckIds.SOURCE_BINLOG_EXPIRY, "源库 binlog 保留时长检查", CheckGroup.PARAMETER, Side.SOURCE);
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        Map<String, String> parameters = facts.source().parameters();

        String seconds = parameters.get("binlog_expire_logs_seconds");
        if (seconds != null) {
            return judge("binlog_expire_logs_seconds", parse(seconds), 1L);
        }
        String days = parameters.get("expire_logs_days");
        if (days != null) {
            return judge("expire_logs_days", parse(days), 1L);
        }
        return Evaluation.notApplicable(
                "当前版本未提供 binlog_expire_logs_seconds 或 expire_logs_days，无法判断保留时长");
    }

    private static Evaluation judge(String parameter, long value, long minimum) {
        if (value < minimum) {
            return Evaluation.fail(
                    parameter + " 为 " + value + "，binlog 不会长期保留",
                    String.valueOf(value),
                    "大于 0（建议至少 1 天）",
                    "调大 binlog 保留时长，避免全量迁移期间 binlog 被清理导致增量无法追平");
        }
        String note = parameter + " = " + value;
        if ("binlog_expire_logs_seconds".equals(parameter)) {
            note += "（约 " + Math.max(1, value / ONE_DAY_SECONDS) + " 天）";
        } else {
            note += "（天）";
        }
        return new Evaluation(Verdict.PASS, List.of(Finding.note(note)));
    }

    private static long parse(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
