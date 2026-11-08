package io.opendrs.engine.replay.mapping;

import io.opendrs.common.metadata.TableId;
import io.opendrs.common.selection.SchemaMapping;
import io.opendrs.common.selection.TableMapping;
import io.opendrs.common.selection.TableMappings;
import io.opendrs.common.selection.TableSelection;
import io.opendrs.engine.replay.exception.MappingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 *
 * <p>选择本身只有两种形态（整库 / 指定表），映射必须落在所选范围内：整库时不能映射被
 * {@code excludeTables} 排除的表，指定表时不能映射没被选中的表。空选择表示「全部」，此时任何
 * 映射都是候选，只做重名冲突检查。
 */
public class MappingValidator {

    public void validate(TableSelection selection) {
        if (selection == null) {
            return;
        }
        if (!selection.schemas().isEmpty() && !selection.tables().isEmpty()) {
            throw new MappingException(
                    "tables must not mix schemas and tables: select whole schemas or individual tables,"
                            + " got schemas=" + selection.schemas() + " tables=" + selection.tables());
        }

        TableMappings mappings = selection.mappings();
        if (mappings == null) {
            return;
        }

        Map<String, String> schemaMap = validateSchemaMappings(mappings.schema(), selection);
        validateTableMappings(mappings.tables(), selection, schemaMap);
    }

    public String resolve(TableSelection tables, String sourceSchema, String sourceTable) {
        TableId ref = resolveTableId(tables, sourceSchema, sourceTable);
        return ref.getSchema() + "." + ref.getTable();
    }

    public TableId resolveTableId(TableSelection tables, String sourceSchema, String sourceTable) {
        TableMappings mappings = tables == null ? null : tables.mappings();
        if (mappings != null && mappings.tables() != null) {
            for (TableMapping mapping : mappings.tables()) {
                if (sourceSchema.equals(mapping.sourceSchema()) && sourceTable.equals(mapping.sourceTable())) {
                    return TableId.of(mapping.targetSchema(), mapping.targetTable());
                }
            }
        }
        if (mappings != null && mappings.schema() != null) {
            for (SchemaMapping mapping : mappings.schema()) {
                if (sourceSchema.equals(mapping.source())) {
                    return TableId.of(mapping.target(), sourceTable);
                }
            }
        }
        return TableId.of(sourceSchema, sourceTable);
    }

    /**
     * Applies schema/table mappings onto expanded source tables. Duplicate target coordinates
     * use the same conflict rule as {@link #validate}.
     */
    public List<TableId> mapTargets(TableSelection selection, List<TableId> sourceTables) {
        List<TableId> safe = sourceTables == null ? List.of() : sourceTables;
        Set<TableId> targets = new LinkedHashSet<>();
        for (TableId source : safe) {
            TableId mapped = resolveTableId(selection, source.getSchema(), source.getTable());
            if (!targets.add(mapped)) {
                throw new MappingException(
                        "Two tables map to the same target: " + mapped.getSchema() + "." + mapped.getTable());
            }
        }
        return new ArrayList<>(targets);
    }

    private Map<String, String> validateSchemaMappings(
            List<SchemaMapping> schemaMappings,
            TableSelection selection) {
        Map<String, String> schemaMap = new HashMap<>();
        Set<String> targets = new HashSet<>();
        if (schemaMappings == null) {
            return schemaMap;
        }
        for (SchemaMapping mapping : schemaMappings) {
            String source = mapping.source();
            String target = mapping.target();
            if (schemaMap.containsKey(source)) {
                throw new MappingException("Duplicate schema mapping source: " + source);
            }
            if (!targets.add(target)) {
                throw new MappingException("Two schemas map to the same target schema: " + target);
            }
            if (!coversSchema(selection, source)) {
                throw new MappingException("Schema mapping source is not in the selection: " + source);
            }
            schemaMap.put(source, target);
        }
        return schemaMap;
    }

    private void validateTableMappings(
            List<TableMapping> tableMappings,
            TableSelection selection,
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
                throw new MappingException("Duplicate table mapping source: " + sourceKey);
            }
            if (!targets.add(targetKey)) {
                throw new MappingException("Two tables map to the same target: " + targetKey);
            }

            if (!coversSchema(selection, mapping.sourceSchema())) {
                throw new MappingException(
                        "Table mapping schema is not in the selection: " + mapping.sourceSchema());
            }
            if (selection.isSchemaExcluded(mapping.sourceSchema())) {
                throw new MappingException("Mapped table is in an excluded schema: " + sourceKey);
            }
            if (!selection.isSchemaLevel()
                    && !containsTable(selection.tables(), mapping.sourceSchema(), mapping.sourceTable())) {
                throw new MappingException("Mapped table is not in the selection: " + sourceKey);
            }
            if (selection.isTableExcluded(mapping.sourceSchema(), mapping.sourceTable())) {
                throw new MappingException("Mapped table is listed in excludeTables: " + sourceKey);
            }

            String schemaTarget = schemaMap.get(mapping.sourceSchema());
            if (schemaTarget != null && !schemaTarget.equals(mapping.targetSchema())) {
                throw new MappingException(
                        "Table mapping targetSchema conflicts with schema mapping: "
                                + mapping.sourceSchema() + " → " + schemaTarget
                                + ", but table " + mapping.sourceTable()
                                + " targets " + mapping.targetSchema());
            }
        }
    }

    /** "All" covers every schema; table-level selections cover the schemas their tables live in. */
    private static boolean coversSchema(TableSelection selection, String schema) {
        if (selection.includesWholeSchema(schema)) {
            return true;
        }
        for (TableId table : selection.tables()) {
            if (table.getSchema().equalsIgnoreCase(schema)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTable(List<TableId> tables, String schema, String table) {
        for (TableId candidate : tables) {
            if (candidate.getSchema().equalsIgnoreCase(schema) && candidate.getTable().equalsIgnoreCase(table)) {
                return true;
            }
        }
        return false;
    }
}
