package io.opendrs.migration.service;

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.migration.api.request.SchemaMapping;
import io.opendrs.migration.api.request.SchemaObject;
import io.opendrs.migration.api.request.TableMapping;
import io.opendrs.migration.api.request.TableMappings;
import io.opendrs.migration.api.request.TableSelection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 映射冲突与对象选择校验。
 *
 * <p>解析优先级（无 naming/LOWER/UPPER 字段）：
 * <ol>
 *   <li>该表存在 table mapping → 使用其 targetSchema.targetTable</li>
 *   <li>否则若 schema mapping 命中 → targetSchema + 原表名</li>
 *   <li>否则保持原 schema.table</li>
 * </ol>
 * 例：SCOTT.EMP + schema SCOTT→scott → scott.EMP；
 * HR.EMPLOYEES + table map → hr.emp；
 * HR.DEPARTMENTS 无映射 → HR.DEPARTMENTS。
 */
@Component
public class MappingValidator {

    public void validate(TableSelection tables) {
        if (tables == null || tables.objects() == null || tables.objects().isEmpty()) {
            throw AppException.of(ErrorCode.PARAM_INVALID, "tables.objects must not be empty");
        }

        Map<String, SchemaObject> objectsBySchema = new LinkedHashMap<>();
        for (SchemaObject object : tables.objects()) {
            if (object.schema() == null || object.schema().isBlank()) {
                throw AppException.of(ErrorCode.PARAM_INVALID, "objects.schema is required");
            }
            if (objectsBySchema.containsKey(object.schema())) {
                throw AppException.of(
                        ErrorCode.PARAM_INVALID,
                        "Duplicate schema in objects: " + object.schema());
            }
            boolean allTables = Boolean.TRUE.equals(object.allTables());
            boolean hasTables = object.tables() != null && !object.tables().isEmpty();
            if (!allTables && !hasTables) {
                throw AppException.of(
                        ErrorCode.PARAM_INVALID,
                        "objects item for schema " + object.schema()
                                + " must set allTables=true or a non-empty tables list");
            }
            objectsBySchema.put(object.schema(), object);
        }

        TableMappings mappings = tables.mappings();
        if (mappings == null) {
            return;
        }

        Map<String, String> schemaMap = validateSchemaMappings(mappings.schema(), objectsBySchema);
        validateTableMappings(mappings.tables(), objectsBySchema, schemaMap);
    }

    public String resolve(TableSelection tables, String sourceSchema, String sourceTable) {
        TableMappings mappings = tables == null ? null : tables.mappings();
        if (mappings != null && mappings.tables() != null) {
            for (TableMapping mapping : mappings.tables()) {
                if (sourceSchema.equals(mapping.sourceSchema()) && sourceTable.equals(mapping.sourceTable())) {
                    return mapping.targetSchema() + "." + mapping.targetTable();
                }
            }
        }
        if (mappings != null && mappings.schema() != null) {
            for (SchemaMapping mapping : mappings.schema()) {
                if (sourceSchema.equals(mapping.source())) {
                    return mapping.target() + "." + sourceTable;
                }
            }
        }
        return sourceSchema + "." + sourceTable;
    }

    private Map<String, String> validateSchemaMappings(
            List<SchemaMapping> schemaMappings,
            Map<String, SchemaObject> objectsBySchema) {
        Map<String, String> schemaMap = new HashMap<>();
        Set<String> targets = new HashSet<>();
        if (schemaMappings == null) {
            return schemaMap;
        }
        for (SchemaMapping mapping : schemaMappings) {
            String source = mapping.source();
            String target = mapping.target();
            if (schemaMap.containsKey(source)) {
                throw AppException.of(
                        ErrorCode.PARAM_INVALID,
                        "Duplicate schema mapping source: " + source);
            }
            if (!targets.add(target)) {
                throw AppException.of(
                        ErrorCode.PARAM_INVALID,
                        "Two schemas map to the same target schema: " + target);
            }
            if (!objectsBySchema.containsKey(source)) {
                throw AppException.of(
                        ErrorCode.PARAM_INVALID,
                        "Schema mapping source is not in objects selection: " + source);
            }
            schemaMap.put(source, target);
        }
        return schemaMap;
    }

    private void validateTableMappings(
            List<TableMapping> tableMappings,
            Map<String, SchemaObject> objectsBySchema,
            Map<String, String> schemaMap) {
        if (tableMappings == null) {
            return;
        }
        Set<String> sources = new HashSet<>();
        Set<String> targets = new HashSet<>();
        for (TableMapping mapping : tableMappings) {
            String sourceKey = mapping.sourceSchema() + "." + mapping.sourceTable();
            String targetKey = mapping.targetSchema() + "." + mapping.targetTable();
            if (!sources.add(sourceKey)) {
                throw AppException.of(
                        ErrorCode.PARAM_INVALID,
                        "Duplicate table mapping source: " + sourceKey);
            }
            if (!targets.add(targetKey)) {
                throw AppException.of(
                        ErrorCode.PARAM_INVALID,
                        "Two tables map to the same target: " + targetKey);
            }

            SchemaObject object = objectsBySchema.get(mapping.sourceSchema());
            if (object == null) {
                throw AppException.of(
                        ErrorCode.PARAM_INVALID,
                        "Table mapping schema is not in objects selection: " + mapping.sourceSchema());
            }
            if (!Boolean.TRUE.equals(object.allTables())) {
                if (object.tables() == null || !object.tables().contains(mapping.sourceTable())) {
                    throw AppException.of(
                            ErrorCode.PARAM_INVALID,
                            "Mapped table is not in objects.tables: " + sourceKey);
                }
            }
            if (object.excludeTables() != null && object.excludeTables().contains(mapping.sourceTable())) {
                throw AppException.of(
                        ErrorCode.PARAM_INVALID,
                        "Mapped table is listed in excludeTables: " + sourceKey);
            }

            String schemaTarget = schemaMap.get(mapping.sourceSchema());
            if (schemaTarget != null && !schemaTarget.equals(mapping.targetSchema())) {
                throw AppException.of(
                        ErrorCode.PARAM_INVALID,
                        "Table mapping targetSchema conflicts with schema mapping: "
                                + mapping.sourceSchema() + " → " + schemaTarget
                                + ", but table " + mapping.sourceTable()
                                + " targets " + mapping.targetSchema());
            }
        }
    }
}
