package io.opendrs.engine.precheck.rule;

import io.opendrs.engine.precheck.CheckGroup;
import io.opendrs.engine.precheck.CheckIds;
import io.opendrs.engine.precheck.CheckMeta;
import io.opendrs.engine.precheck.CheckRule;
import io.opendrs.engine.precheck.DependencyUnavailableException;
import io.opendrs.engine.precheck.Evaluation;
import io.opendrs.engine.precheck.Finding;
import io.opendrs.engine.precheck.PrecheckContext;
import io.opendrs.engine.precheck.Side;
import io.opendrs.engine.precheck.Verdict;
import io.opendrs.engine.precheck.fact.EndpointFacts;
import io.opendrs.engine.precheck.fact.FactStore;
import io.opendrs.jdbc.JdbcConnectionConfig;
import java.util.List;

/**
 * Opens the endpoint. Besides being a check in its own right this is the root cause the rest of the
 * side depends on: every other rule reaches the database through {@link EndpointFacts}, which turns
 * this failure into {@code BLOCKED} rows instead of a column of duplicate errors.
 */
public final class ConnectionCheck implements CheckRule {

    private final Side side;

    public ConnectionCheck(Side side) {
        this.side = side;
    }

    @Override
    public CheckMeta meta() {
        boolean source = side == Side.SOURCE;
        return new CheckMeta(
                source ? CheckIds.SOURCE_CONNECTION : CheckIds.TARGET_CONNECTION,
                source ? "源库连接是否成功" : "目标库连接是否成功",
                CheckGroup.NETWORK,
                side);
    }

    @Override
    public Evaluation evaluate(PrecheckContext context, FactStore facts) {
        EndpointFacts endpoint = facts.of(side);
        try {
            endpoint.connection().ping();
        } catch (DependencyUnavailableException ex) {
            return Evaluation.fail(
                    endpointLabel() + "连接失败",
                    ex.getMessage(),
                    "网络可达、端口开放、账号密码正确",
                    "检查地址与端口是否正确、防火墙是否放通，确认账号可远程登录后重新校验");
        }
        JdbcConnectionConfig config = endpoint.config();
        return new Evaluation(Verdict.PASS, List.of(Finding.note(
                config.getHost() + ":" + config.getPort() + "/" + config.getDatabase())));
    }

    private String endpointLabel() {
        return side == Side.SOURCE ? "源库" : "目标库";
    }
}
