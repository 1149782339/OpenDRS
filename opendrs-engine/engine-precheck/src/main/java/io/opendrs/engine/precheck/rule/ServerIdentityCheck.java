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
 * Bidirectional sync connects two live MySQL regions that must not look like one server:
 * {@code server_id} collisions make Debezium's registration ambiguous, and identical
 * {@code server_uuid} would make one side's local transactions indistinguishable from the other's
 * in the GTID-based loop filter.
 */
public final class ServerIdentityCheck implements CheckRule {

    @Override
    public CheckMeta meta() {
        return new CheckMeta(
                CheckIds.COMPAT_SERVER_IDENTITY, "两端 server_id / server_uuid 唯一性检查",
                CheckGroup.PARAMETER, Side.BOTH);
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        Map<String, String> source = facts.source().parameters();
        Map<String, String> target = facts.target().parameters();
        String sourceId = source.get("server_id");
        String targetId = target.get("server_id");
        String sourceUuid = source.get("server_uuid");
        String targetUuid = target.get("server_uuid");
        if (sourceId != null && sourceId.equals(targetId)) {
            return Evaluation.fail(
                    "两端 server_id 相同（" + sourceId + "）",
                    sourceId,
                    "不同的 server_id",
                    "为其中一个实例配置不同的 server_id 后重新校验");
        }
        if (sourceUuid != null && sourceUuid.equals(targetUuid)) {
            return Evaluation.fail(
                    "两端 server_uuid 相同（" + sourceUuid + "）",
                    sourceUuid,
                    "不同的 server_uuid",
                    "确认两端不是同一个实例（或由其克隆且未重新生成 uuid）后重新校验");
        }
        return new Evaluation(Verdict.PASS, List.of(Finding.note(
                "server_id " + sourceId + " ↔ " + targetId + "，server_uuid 两端不同")));
    }
}
