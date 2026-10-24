package io.opendrs.engine.precheck.profile;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.engine.precheck.CheckIds;
import io.opendrs.engine.precheck.PrecheckProfile;
import io.opendrs.engine.precheck.RuleBinding;
import io.opendrs.engine.precheck.RulePack;
import io.opendrs.engine.precheck.RuleRegistry;
import io.opendrs.engine.precheck.Severity;
import io.opendrs.engine.precheck.Side;
import io.opendrs.engine.precheck.rule.ConnectionCheck;
import io.opendrs.engine.precheck.rule.GtidNextPermissionCheck;
import io.opendrs.engine.precheck.rule.InstanceStateCheck;
import io.opendrs.engine.precheck.rule.MysqlBinlogExpiryCheck;
import io.opendrs.engine.precheck.rule.MysqlEngineCheck;
import io.opendrs.engine.precheck.rule.MysqlParameterCheck;
import io.opendrs.engine.precheck.rule.PermissionCheck;
import io.opendrs.engine.precheck.rule.ServerIdentityCheck;
import io.opendrs.engine.precheck.rule.SourcePrimaryKeyCheck;
import io.opendrs.engine.precheck.rule.TargetTablePresenceCheck;
import io.opendrs.engine.precheck.rule.VersionCheck;
import java.util.ArrayList;
import java.util.List;

/**
 * The MySQL → MySQL checklist, shared by the one-way and the bidirectional topology. The profile
 * is keyed by endpoint types alone (MySQL → MySQL), so it must serve both; {@code bidirectional}
 * in the context gates the checks that only a two-way pair needs — reverse capture prerequisites
 * on the target (binlog, GTID) and the server-identity check behind the GTID loop filter.
 *
 * <p>No schema migration runs for this direction: {@code target.table_presence} is a blocker
 * instead of {@code target.table_conflict}.
 */
public final class MysqlBidirectionalProfile implements PrecheckProfile {

    public static final String ID = "mysql-bidirectional";

    private static final int MYSQL_MIN_MAJOR = 8;
    private static final int MYSQL_MIN_MINOR = 0;

    /** Both directions capture binlog, so both users need the full CDC privilege set. */
    private static final List<String> MYSQL_CDC_PRIVILEGES = List.of(
            "SELECT", "RELOAD", "REPLICATION SLAVE", "REPLICATION CLIENT", "SHOW DATABASES");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "MySQL → MySQL";
    }

    @Override
    public DatabaseType sourceType() {
        return DatabaseType.MYSQL;
    }

    @Override
    public DatabaseType targetType() {
        return DatabaseType.MYSQL;
    }

    @Override
    public List<RulePack> packs() {
        return List.of(commonPack(), sourcePack(), targetPack(), compatibilityPack());
    }

    private static RulePack commonPack() {
        return context -> List.of(
                new RuleBinding(CheckIds.SOURCE_CONNECTION, Severity.BLOCKER),
                new RuleBinding(CheckIds.TARGET_CONNECTION, Severity.BLOCKER));
    }

    private static RulePack sourcePack() {
        return context -> {
            List<RuleBinding> bindings = new ArrayList<>(List.of(
                    new RuleBinding(CheckIds.SOURCE_VERSION, Severity.BLOCKER),
                    new RuleBinding(CheckIds.SOURCE_PERMISSION, Severity.BLOCKER),
                    new RuleBinding(CheckIds.SOURCE_LOG_BIN, Severity.BLOCKER),
                    new RuleBinding(CheckIds.SOURCE_BINLOG_FORMAT, Severity.BLOCKER),
                    new RuleBinding(CheckIds.SOURCE_BINLOG_ROW_IMAGE, Severity.BLOCKER),
                    new RuleBinding(CheckIds.SOURCE_BINLOG_EXPIRY, Severity.WARNING),
                    new RuleBinding(CheckIds.SOURCE_GTID_MODE, Severity.BLOCKER),
                    new RuleBinding(CheckIds.SOURCE_UNSUPPORTED_ENGINE, Severity.BLOCKER),
                    new RuleBinding(CheckIds.SOURCE_PRIMARY_KEY, Severity.WARNING)));
            // The reverse direction replays into the source, so its login must be able to tag
            // transactions by gtid_next — bidirectional-only, like the target-side prerequisites.
            if (context.bidirectional()) {
                bindings.add(new RuleBinding(CheckIds.SOURCE_GTID_NEXT, Severity.BLOCKER));
            }
            return bindings;
        };
    }

    private static RulePack targetPack() {
        return context -> {
            List<RuleBinding> bindings = new ArrayList<>(List.of(
                    new RuleBinding(CheckIds.TARGET_VERSION, Severity.BLOCKER),
                    new RuleBinding(CheckIds.TARGET_PERMISSION, Severity.BLOCKER),
                    new RuleBinding(CheckIds.TARGET_INSTANCE_STATE, Severity.BLOCKER)));
            // Reverse capture reads the target's binlog, so these are bidirectional-only:
            // a one-way MySQL → MySQL task must not fail because the target never captures.
            if (context.bidirectional()) {
                bindings.add(new RuleBinding(CheckIds.TARGET_LOG_BIN, Severity.BLOCKER));
                bindings.add(new RuleBinding(CheckIds.TARGET_BINLOG_FORMAT, Severity.BLOCKER));
                bindings.add(new RuleBinding(CheckIds.TARGET_GTID_MODE, Severity.BLOCKER));
                // Forward replay tags transactions by gtid_next on the target.
                bindings.add(new RuleBinding(CheckIds.TARGET_GTID_NEXT, Severity.BLOCKER));
            }
            bindings.add(new RuleBinding(CheckIds.TARGET_TABLE_PRESENCE, Severity.BLOCKER));
            return bindings;
        };
    }

    private static RulePack compatibilityPack() {
        // The GTID loop filter exists only in a bidirectional pair; one-way tasks have no
        // cross-direction GTIDs to tell apart, so server identity is irrelevant there.
        return context -> context.bidirectional()
                ? List.of(new RuleBinding(CheckIds.COMPAT_SERVER_IDENTITY, Severity.BLOCKER))
                : List.of();
    }

    /** Every rule this direction can bind. Assembled once by the engine. */
    public static RuleRegistry registry() {
        return new RuleRegistry(List.of(
                new ConnectionCheck(Side.SOURCE),
                new ConnectionCheck(Side.TARGET),
                new VersionCheck(Side.SOURCE, MYSQL_MIN_MAJOR, MYSQL_MIN_MINOR),
                new VersionCheck(Side.TARGET, MYSQL_MIN_MAJOR, MYSQL_MIN_MINOR),
                new PermissionCheck(Side.SOURCE, MYSQL_CDC_PRIVILEGES),
                new PermissionCheck(Side.TARGET, MYSQL_CDC_PRIVILEGES),
                new GtidNextPermissionCheck(Side.SOURCE),
                new GtidNextPermissionCheck(Side.TARGET),
                new InstanceStateCheck(),
                new MysqlParameterCheck(
                        CheckIds.SOURCE_LOG_BIN,
                        "源库 binlog 是否开启",
                        "log_bin",
                        "ON",
                        MysqlParameterCheck::isOn,
                        MysqlParameterCheck.Absent.FAIL,
                        "在 my.cnf 中开启 log_bin 并重启实例后重新校验"),
                new MysqlParameterCheck(
                        CheckIds.SOURCE_BINLOG_FORMAT,
                        "源库 binlog 格式检查",
                        "binlog_format",
                        "ROW",
                        MysqlParameterCheck.equalTo("ROW"),
                        MysqlParameterCheck.Absent.FAIL,
                        "把 binlog_format 改为 ROW 后重新校验，STATEMENT/MIXED 无法还原行级变更"),
                new MysqlParameterCheck(
                        CheckIds.SOURCE_BINLOG_ROW_IMAGE,
                        "源库 binlog_row_image 参数是否为 FULL",
                        "binlog_row_image",
                        "FULL",
                        MysqlParameterCheck.equalTo("FULL"),
                        MysqlParameterCheck.Absent.FAIL,
                        "把 binlog_row_image 改为 FULL，否则变更前后镜像不完整、无法还原整行"),
                new MysqlBinlogExpiryCheck(),
                new MysqlParameterCheck(
                        CheckIds.SOURCE_GTID_MODE,
                        "源库 gtid_mode 检查（双向同步防回环依赖 GTID）",
                        "gtid_mode",
                        "ON",
                        MysqlParameterCheck.equalTo("ON"),
                        MysqlParameterCheck.Absent.FAIL,
                        "双向同步通过 GTID 标记回放事务防止回环，必须开启 gtid_mode"),
                new MysqlEngineCheck(),
                new SourcePrimaryKeyCheck(),
                new MysqlParameterCheck(
                        CheckIds.TARGET_LOG_BIN,
                        "目标库 binlog 是否开启（反向捕获依赖）",
                        "log_bin",
                        "ON",
                        MysqlParameterCheck::isOn,
                        MysqlParameterCheck.Absent.FAIL,
                        "反向方向要捕获目标库的 binlog，请在 my.cnf 中开启 log_bin 后重新校验",
                        Side.TARGET),
                new MysqlParameterCheck(
                        CheckIds.TARGET_BINLOG_FORMAT,
                        "目标库 binlog 格式检查（反向捕获依赖）",
                        "binlog_format",
                        "ROW",
                        MysqlParameterCheck.equalTo("ROW"),
                        MysqlParameterCheck.Absent.FAIL,
                        "把目标库 binlog_format 改为 ROW 后重新校验",
                        Side.TARGET),
                new MysqlParameterCheck(
                        CheckIds.TARGET_GTID_MODE,
                        "目标库 gtid_mode 检查（双向同步防回环依赖 GTID）",
                        "gtid_mode",
                        "ON",
                        MysqlParameterCheck.equalTo("ON"),
                        MysqlParameterCheck.Absent.FAIL,
                        "双向同步通过 GTID 标记回放事务防止回环，目标库也必须开启 gtid_mode",
                        Side.TARGET),
                new TargetTablePresenceCheck(),
                new ServerIdentityCheck()));
    }
}
