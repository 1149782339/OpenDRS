package io.opendrs.common.i18n;

/**
 * Registry of user-facing message keys. Each constant maps to identically named entries in
 * {@code messages.properties} / {@code messages_zh_CN.properties}; the enum is the compile-time
 * contract, the properties files hold the actual translated text.
 *
 * <p>Rules of the house:
 *
 * <ul>
 *   <li>Never concatenate translated text with values at the throw site — pass values as args and
 *       let the message template place them ({0}, {1}, ...), because word order differs by language.
 *   <li>Dynamic detail that cannot be keyed (e.g. a driver exception message) goes into an arg,
 *       never into the template.
 * </ul>
 */
public enum ErrorMessage {

    /** Generic parameter validation failure, no field detail available. */
    PARAM_INVALID("error.param.invalid"),
    /** Parameter validation failure; {0} is the joined field details. */
    PARAM_INVALID_DETAIL("error.param.invalidDetail"),
    /** Malformed request body or unconvertible parameter. */
    PARAM_BAD_REQUEST("error.param.badRequest"),

    CONNECTION_NAME_EXISTS("error.connection.nameExists"),
    CONNECTION_NOT_FOUND("error.connection.notFound"),
    CONNECTION_IN_USE("error.connection.inUse"),
    CONNECTION_TEST_FAILED("error.connection.testFailed"),
    CONNECTION_SCHEMA_REQUIRED("error.connection.schemaRequired"),
    CONNECTION_LIST_SCHEMAS_FAILED("error.connection.listSchemasFailed"),
    CONNECTION_LIST_TABLES_FAILED("error.connection.listTablesFailed"),
    CONNECTION_LIST_REPLICAS_FAILED("error.connection.listReplicasFailed"),
    CONNECTION_SERVER_UUID_FAILED("error.connection.serverUuidFailed"),

    TASK_NOT_FOUND("error.task.notFound"),
    TASK_NAME_EXISTS("error.task.nameExists"),
    TASK_SOURCE_NOT_FOUND("error.task.sourceNotFound"),
    TASK_TARGET_NOT_FOUND("error.task.targetNotFound"),
    TASK_BIDIR_MYSQL_ONLY("error.task.bidirMysqlOnly"),
    TASK_BIDIR_FULL_INC_ONLY("error.task.bidirFullIncOnly"),
    TASK_MAPPING_INVALID("error.task.mappingInvalid"),
    TASK_SELECTION_ALL_EMPTY("error.task.selection.allEmpty"),
    TASK_SELECTION_SCHEMA_NEEDS_SCHEMAS("error.task.selection.schemaNeedsSchemas"),
    TASK_SELECTION_TABLE_NEEDS_TABLES("error.task.selection.tableNeedsTables"),
    TASK_CANNOT_START_FROM("error.task.cannotStartFrom"),
    TASK_CANNOT_PAUSE_FROM("error.task.cannotPauseFrom"),
    TASK_CANNOT_TERMINATE_FROM("error.task.cannotTerminateFrom"),
    TASK_MUST_TERMINATE_BEFORE_DELETE("error.task.mustTerminateBeforeDelete"),
    TASK_CANNOT_PRECHECK_FROM("error.task.cannotPrecheckFrom"),
    TASK_COMPLETE_PRECHECK_CONFLICT("error.task.completePrecheckConflict"),

    PRECHECK_INTERRUPTED("error.precheck.interrupted"),
    PRECHECK_NO_RESULTS("error.precheck.noResults"),
    PRECHECK_NOT_SUPPORTED("error.precheck.notSupported"),

    SYSTEM_DB_ERROR("error.system.db"),
    SYSTEM_INTERNAL_ERROR("error.system.internal");

    private final String key;

    ErrorMessage(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
