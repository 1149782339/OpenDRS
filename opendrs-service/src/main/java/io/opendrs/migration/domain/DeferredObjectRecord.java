package io.opendrs.migration.domain;

import io.opendrs.engine.jdbc.deferred.DeferredObject;
import io.opendrs.engine.jdbc.deferred.DeferredObjectType;
import java.time.Instant;

/** Row in {@code task_deferred_objects}: one secondary object of a target table. */
public class DeferredObjectRecord {

    private Long id;
    private Long pipelineId;
    private String schemaName;
    private String tableName;
    private DeferredObjectType objectType;
    private String objectName;
    private String createDdl;
    private String dropDdl;
    private String postDdl;
    private int buildOrder;
    private DeferredObjectState state;
    private int attempts;
    private String lastError;
    private Instant updatedAt;

    public static DeferredObjectRecord of(long pipelineId, DeferredObject object) {
        DeferredObjectRecord row = new DeferredObjectRecord();
        row.pipelineId = pipelineId;
        row.schemaName = object.schema();
        row.tableName = object.table();
        row.objectType = object.type();
        row.objectName = object.name();
        row.createDdl = object.createDdl();
        row.dropDdl = object.dropDdl();
        row.postDdl = object.postDdl();
        row.buildOrder = object.buildOrder();
        row.state = DeferredObjectState.CAPTURED;
        return row;
    }

    public DeferredObject toObject() {
        return new DeferredObject(schemaName, tableName, objectType, objectName, createDdl, dropDdl, postDdl,
                buildOrder);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPipelineId() {
        return pipelineId;
    }

    public void setPipelineId(Long pipelineId) {
        this.pipelineId = pipelineId;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public DeferredObjectType getObjectType() {
        return objectType;
    }

    public void setObjectType(DeferredObjectType objectType) {
        this.objectType = objectType;
    }

    public String getObjectName() {
        return objectName;
    }

    public void setObjectName(String objectName) {
        this.objectName = objectName;
    }

    public String getCreateDdl() {
        return createDdl;
    }

    public void setCreateDdl(String createDdl) {
        this.createDdl = createDdl;
    }

    public String getDropDdl() {
        return dropDdl;
    }

    public void setDropDdl(String dropDdl) {
        this.dropDdl = dropDdl;
    }

    public String getPostDdl() {
        return postDdl;
    }

    public void setPostDdl(String postDdl) {
        this.postDdl = postDdl;
    }

    public int getBuildOrder() {
        return buildOrder;
    }

    public void setBuildOrder(int buildOrder) {
        this.buildOrder = buildOrder;
    }

    public DeferredObjectState getState() {
        return state;
    }

    public void setState(DeferredObjectState state) {
        this.state = state;
    }

    public int getAttempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
