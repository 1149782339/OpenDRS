package io.opendrs.engine.precheck.profile;

import io.opendrs.common.metadata.DatabaseType;
import io.opendrs.engine.precheck.CheckIds;
import io.opendrs.engine.precheck.PrecheckProfile;
import io.opendrs.engine.precheck.RuleBinding;
import io.opendrs.engine.precheck.RulePack;
import io.opendrs.engine.precheck.RuleRegistry;
import io.opendrs.engine.precheck.Severity;
import io.opendrs.engine.precheck.Side;
import io.opendrs.engine.precheck.rule.CharsetCompatibilityCheck;
import io.opendrs.engine.precheck.rule.ConnectionCheck;
import io.opendrs.engine.precheck.rule.InstanceStateCheck;
import io.opendrs.engine.precheck.rule.MysqlBinlogExpiryCheck;
import io.opendrs.engine.precheck.rule.MysqlEngineCheck;
import io.opendrs.engine.precheck.rule.MysqlParameterCheck;
import io.opendrs.engine.precheck.rule.PermissionCheck;
import io.opendrs.engine.precheck.rule.SourcePrimaryKeyCheck;
import io.opendrs.engine.precheck.rule.TargetTableConflictCheck;
import io.opendrs.engine.precheck.rule.TimeSemanticsCheck;
import io.opendrs.engine.precheck.rule.VersionCheck;
import java.util.List;

/**
 * The MySQL to PostgreSQL checklist.
 *
 * <p>This class is the single place where "which checks exist for this direction" lives: the rules
 * themselves contain no knowledge of each other, and neither the runner nor the UI enumerates
 * anything. Adding a check is a new rule class plus one binding below.
 *
 * <p>Severity is assigned here, not in the rule, because the same check can be a blocker in one
 * direction and a warning in another.
 */
public final class MysqlToPgProfile implements PrecheckProfile {

    public static final String ID = "mysql-to-postgresql";

    /** Minimum server versions this product migrates from and to. */
    private static final int MYSQL_MIN_MAJOR = 8;
    private static final int MYSQL_MIN_MINOR = 0;
    private static final int POSTGRES_MIN_MAJOR = 17;
    private static final int POSTGRES_MIN_MINOR = 0;

    /**
     * What the CDC user needs on the source: read the rows, read the binlog, keep the position and
     * take the snapshot lock. Missing any of them fails the run later, so all are blockers.
     */
    private static final List<String> MYSQL_SOURCE_PRIVILEGES = List.of(
            "SELECT", "RELOAD", "REPLICATION SLAVE", "REPLICATION CLIENT", "SHOW DATABASES");

    /** What the applier needs on the target: use the schema and create objects in it. */
    private static final List<String> POSTGRES_TARGET_PRIVILEGES = List.of("USAGE", "CREATE");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return "MySQL → PostgreSQL";
    }

    @Override
    public DatabaseType sourceType() {
        return DatabaseType.MYSQL;
    }

    @Override
    public DatabaseType targetType() {
        return DatabaseType.POSTGRESQL;
    }

    @Override
    public List<RulePack> packs() {
        return List.of(commonPack(), sourcePack(), targetPack(), compatibilityPack());
    }

    /** Both endpoints must answer before anything else is worth checking. */
    private static RulePack commonPack() {
        return context -> List.of(
                new RuleBinding(CheckIds.SOURCE_CONNECTION, Severity.BLOCKER),
                new RuleBinding(CheckIds.TARGET_CONNECTION, Severity.BLOCKER));
    }

    private static RulePack sourcePack() {
        return context -> List.of(
                new RuleBinding(CheckIds.SOURCE_VERSION, Severity.BLOCKER),
                new RuleBinding(CheckIds.SOURCE_PERMISSION, Severity.BLOCKER),
                new RuleBinding(CheckIds.SOURCE_LOG_BIN, Severity.BLOCKER),
                new RuleBinding(CheckIds.SOURCE_BINLOG_FORMAT, Severity.BLOCKER),
                new RuleBinding(CheckIds.SOURCE_BINLOG_ROW_IMAGE, Severity.BLOCKER),
                new RuleBinding(CheckIds.SOURCE_LOG_SLAVE_UPDATES, Severity.WARNING),
                new RuleBinding(CheckIds.SOURCE_BINLOG_EXPIRY, Severity.WARNING),
                new RuleBinding(CheckIds.SOURCE_GTID_MODE, Severity.WARNING),
                new RuleBinding(CheckIds.SOURCE_UNSUPPORTED_ENGINE, Severity.BLOCKER),
                new RuleBinding(CheckIds.SOURCE_PRIMARY_KEY, Severity.WARNING));
    }

    private static RulePack targetPack() {
        return context -> List.of(
                new RuleBinding(CheckIds.TARGET_VERSION, Severity.BLOCKER),
                new RuleBinding(CheckIds.TARGET_PERMISSION, Severity.BLOCKER),
                new RuleBinding(CheckIds.TARGET_INSTANCE_STATE, Severity.BLOCKER),
                new RuleBinding(CheckIds.TARGET_TABLE_CONFLICT, Severity.WARNING));
    }

    /** Checks that read both endpoints; they need both connections, so they run last. */
    private static RulePack compatibilityPack() {
        return context -> List.of(
                new RuleBinding(CheckIds.COMPAT_CHARSET, Severity.WARNING),
                new RuleBinding(CheckIds.COMPAT_TIMEZONE, Severity.WARNING));
    }

    /** Every rule this direction can bind. Assembled once by the engine. */
    public static RuleRegistry registry() {
        return new RuleRegistry(List.of(
                new ConnectionCheck(Side.SOURCE),
                new ConnectionCheck(Side.TARGET),
                new VersionCheck(Side.SOURCE, MYSQL_MIN_MAJOR, MYSQL_MIN_MINOR),
                new VersionCheck(Side.TARGET, POSTGRES_MIN_MAJOR, POSTGRES_MIN_MINOR),
                new PermissionCheck(Side.SOURCE, MYSQL_SOURCE_PRIVILEGES),
                new PermissionCheck(Side.TARGET, POSTGRES_TARGET_PRIVILEGES),
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
                new MysqlParameterCheck(
                        CheckIds.SOURCE_LOG_SLAVE_UPDATES,
                        "源库参数 log_slave_updates 检查",
                        "log_slave_updates",
                        "ON",
                        MysqlParameterCheck::isOn,
                        MysqlParameterCheck.Absent.NOT_APPLICABLE,
                        "多级复制链路需要 log_slave_updates=ON，否则备库上的变更不会写入 binlog"),
                new MysqlBinlogExpiryCheck(),
                new MysqlParameterCheck(
                        CheckIds.SOURCE_GTID_MODE,
                        "源库参数 gtid_mode 检查",
                        "gtid_mode",
                        "ON",
                        MysqlParameterCheck.equalTo("ON"),
                        MysqlParameterCheck.Absent.FAIL,
                        "gtid_mode=ON 便于断点续传与主备切换，建议开启"),
                new MysqlEngineCheck(),
                new SourcePrimaryKeyCheck(),
                new TargetTableConflictCheck(),
                new CharsetCompatibilityCheck(),
                new TimeSemanticsCheck()));
    }
}
