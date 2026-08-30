package io.opendrs.migration.domain;

/**
 * 状态机（v1）：CREATED → PRECHECKING → PRECHECKED → STARTING → SCHEMA_SNAPSHOTTING →
 * FULL → INCREMENTAL → STOPPING → STOPPED。任意阶段可进入 FAILED。
 * PRECHECKING 仅在同步 precheck 请求期间存在；崩溃后允许从 PRECHECKING 重试。
 * STOPPING / stop 尚未接线。
 */
public enum TaskState {
    CREATED,
    PRECHECKING,
    PRECHECKED,
    STARTING,
    SCHEMA_SNAPSHOTTING,
    FULL,
    INCREMENTAL,
    STOPPING,
    STOPPED,
    FAILED;

    public boolean canPrecheck() {
        return this == CREATED || this == FAILED || this == PRECHECKED || this == PRECHECKING;
    }

    public boolean canStart() {
        return this == PRECHECKED || this == STOPPED;
    }

    public boolean isRunning() {
        return this == PRECHECKING
                || this == STARTING
                || this == SCHEMA_SNAPSHOTTING
                || this == FULL
                || this == INCREMENTAL
                || this == STOPPING;
    }
}
