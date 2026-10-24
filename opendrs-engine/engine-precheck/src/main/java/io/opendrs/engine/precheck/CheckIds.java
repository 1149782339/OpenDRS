package io.opendrs.engine.precheck;

/**
 * Every check id in one place. Ids are persisted in the result JSON and bound by the profiles, so
 * they are part of the stored contract: add a constant when adding a check, never rename one.
 */
public final class CheckIds {

    public static final String SOURCE_CONNECTION = "source.connection";
    public static final String TARGET_CONNECTION = "target.connection";

    public static final String SOURCE_VERSION = "source.version";
    public static final String TARGET_VERSION = "target.version";

    public static final String SOURCE_PERMISSION = "source.permission";
    public static final String TARGET_PERMISSION = "target.permission";

    public static final String TARGET_INSTANCE_STATE = "target.instance_state";

    public static final String SOURCE_LOG_BIN = "source.log_bin";
    public static final String SOURCE_BINLOG_FORMAT = "source.binlog_format";
    public static final String SOURCE_BINLOG_ROW_IMAGE = "source.binlog_row_image";
    public static final String SOURCE_LOG_SLAVE_UPDATES = "source.log_slave_updates";
    public static final String SOURCE_BINLOG_EXPIRY = "source.binlog_expiry";
    public static final String SOURCE_GTID_MODE = "source.gtid_mode";
    public static final String SOURCE_UNSUPPORTED_ENGINE = "source.unsupported_engine";

    public static final String COMPAT_CHARSET = "compat.charset";
    public static final String COMPAT_TIMEZONE = "compat.timezone";

    public static final String SOURCE_PRIMARY_KEY = "source.primary_key";
    public static final String TARGET_TABLE_CONFLICT = "target.table_conflict";

    public static final String TARGET_LOG_BIN = "target.log_bin";
    public static final String TARGET_BINLOG_FORMAT = "target.binlog_format";
    public static final String TARGET_GTID_MODE = "target.gtid_mode";
    public static final String TARGET_TABLE_PRESENCE = "target.table_presence";
    public static final String COMPAT_SERVER_IDENTITY = "compat.server_identity";

    public static final String SOURCE_GTID_NEXT = "source.gtid_next";
    public static final String TARGET_GTID_NEXT = "target.gtid_next";

    private CheckIds() {
    }
}
