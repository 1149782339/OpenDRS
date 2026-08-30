package io.opendrs.migration.domain;

/**
 * 状态机（v1）：CREATED → STARTING → SCHEMA_SNAPSHOTTING → FULL → INCREMENTAL → STOPPING → STOPPED。
 * 任意阶段可进入 FAILED。STOPPING / stop 尚未接线。
 */
public enum TaskState {
    CREATED,
    STARTING,
    SCHEMA_SNAPSHOTTING,
    FULL,
    INCREMENTAL,
    STOPPING,
    STOPPED,
    FAILED
}
