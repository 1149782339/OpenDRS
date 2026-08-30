package io.opendrs.migration.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opendrs.common.error.AppException;
import io.opendrs.common.error.ErrorCode;
import io.opendrs.migration.api.request.SchemaMapping;
import io.opendrs.migration.api.request.SchemaObject;
import io.opendrs.migration.api.request.TableMapping;
import io.opendrs.migration.api.request.TableMappings;
import io.opendrs.migration.api.request.TableSelection;
import java.util.List;
import org.junit.jupiter.api.Test;

class MappingValidatorTest {

    private final MappingValidator validator = new MappingValidator();

    @Test
    void acceptsValidObjectsAndMappings() {
        TableSelection tables = selection(
                List.of(
                        new SchemaObject("HR", List.of("EMPLOYEES", "DEPARTMENTS"), null, null),
                        new SchemaObject("SCOTT", List.of("EMP"), null, null)),
                new TableMappings(
                        List.of(new SchemaMapping("SCOTT", "scott")),
                        List.of(new TableMapping("HR", "EMPLOYEES", "hr", "emp"))));

        validator.validate(tables);
        assertEquals("scott.EMP", validator.resolve(tables, "SCOTT", "EMP"));
        assertEquals("hr.emp", validator.resolve(tables, "HR", "EMPLOYEES"));
        assertEquals("HR.DEPARTMENTS", validator.resolve(tables, "HR", "DEPARTMENTS"));
    }

    @Test
    void rejectsDuplicateSchemaInObjects() {
        TableSelection tables = selection(
                List.of(
                        new SchemaObject("HR", List.of("EMPLOYEES"), null, null),
                        new SchemaObject("HR", List.of("JOBS"), null, null)),
                null);

        AppException ex = assertThrows(AppException.class, () -> validator.validate(tables));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getCode());
    }

    @Test
    void rejectsEmptyTablesAndAllTablesFalse() {
        TableSelection tables = selection(
                List.of(new SchemaObject("HR", List.of(), false, null)),
                null);

        AppException ex = assertThrows(AppException.class, () -> validator.validate(tables));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getCode());
    }

    @Test
    void rejectsDuplicateSchemaMappingSource() {
        TableSelection tables = selection(
                List.of(new SchemaObject("HR", null, true, null)),
                new TableMappings(
                        List.of(new SchemaMapping("HR", "hr"), new SchemaMapping("HR", "hr2")),
                        null));

        AppException ex = assertThrows(AppException.class, () -> validator.validate(tables));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getCode());
        assertEquals("Duplicate schema mapping source: HR", ex.getMessage());
    }

    @Test
    void rejectsTwoSchemasMappingToSameTarget() {
        TableSelection tables = selection(
                List.of(
                        new SchemaObject("HR", null, true, null),
                        new SchemaObject("SCOTT", null, true, null)),
                new TableMappings(
                        List.of(new SchemaMapping("HR", "hr"), new SchemaMapping("SCOTT", "hr")),
                        null));

        AppException ex = assertThrows(AppException.class, () -> validator.validate(tables));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getCode());
        assertEquals("Two schemas map to the same target schema: hr", ex.getMessage());
    }

    @Test
    void rejectsDuplicateTableMappingSource() {
        TableSelection tables = selection(
                List.of(new SchemaObject("HR", List.of("EMPLOYEES"), null, null)),
                new TableMappings(
                        null,
                        List.of(
                                new TableMapping("HR", "EMPLOYEES", "hr", "emp"),
                                new TableMapping("HR", "EMPLOYEES", "hr", "employees"))));

        AppException ex = assertThrows(AppException.class, () -> validator.validate(tables));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getCode());
        assertEquals("Duplicate table mapping source: HR.EMPLOYEES", ex.getMessage());
    }

    @Test
    void rejectsTwoTablesMappingToSameTarget() {
        TableSelection tables = selection(
                List.of(new SchemaObject("HR", List.of("EMPLOYEES", "JOBS"), null, null)),
                new TableMappings(
                        null,
                        List.of(
                                new TableMapping("HR", "EMPLOYEES", "hr", "emp"),
                                new TableMapping("HR", "JOBS", "hr", "emp"))));

        AppException ex = assertThrows(AppException.class, () -> validator.validate(tables));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getCode());
        assertEquals("Two tables map to the same target: hr.emp", ex.getMessage());
    }

    @Test
    void rejectsCrossLayerSchemaConflict() {
        TableSelection tables = selection(
                List.of(new SchemaObject("HR", List.of("EMPLOYEES"), null, null)),
                new TableMappings(
                        List.of(new SchemaMapping("HR", "hr")),
                        List.of(new TableMapping("HR", "EMPLOYEES", "other", "emp"))));

        AppException ex = assertThrows(AppException.class, () -> validator.validate(tables));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getCode());
    }

    @Test
    void allowsTableRenameWhenTargetSchemaMatchesSchemaMapping() {
        TableSelection tables = selection(
                List.of(new SchemaObject("HR", List.of("EMPLOYEES"), null, null)),
                new TableMappings(
                        List.of(new SchemaMapping("HR", "hr")),
                        List.of(new TableMapping("HR", "EMPLOYEES", "hr", "emp"))));

        validator.validate(tables);
        assertEquals("hr.emp", validator.resolve(tables, "HR", "EMPLOYEES"));
    }

    @Test
    void rejectsMappedSchemaNotInObjects() {
        TableSelection tables = selection(
                List.of(new SchemaObject("HR", null, true, null)),
                new TableMappings(List.of(new SchemaMapping("SCOTT", "scott")), null));

        AppException ex = assertThrows(AppException.class, () -> validator.validate(tables));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getCode());
        assertEquals("Schema mapping source is not in objects selection: SCOTT", ex.getMessage());
    }

    @Test
    void rejectsMappedTableNotInExplicitList() {
        TableSelection tables = selection(
                List.of(new SchemaObject("HR", List.of("DEPARTMENTS"), null, null)),
                new TableMappings(
                        null,
                        List.of(new TableMapping("HR", "EMPLOYEES", "hr", "emp"))));

        AppException ex = assertThrows(AppException.class, () -> validator.validate(tables));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getCode());
        assertEquals("Mapped table is not in objects.tables: HR.EMPLOYEES", ex.getMessage());
    }

    @Test
    void acceptsMappedTableWhenAllTables() {
        TableSelection tables = selection(
                List.of(new SchemaObject("HR", null, true, null)),
                new TableMappings(
                        null,
                        List.of(new TableMapping("HR", "EMPLOYEES", "hr", "emp"))));

        validator.validate(tables);
    }

    @Test
    void rejectsMappedTableThatIsExcluded() {
        TableSelection tables = selection(
                List.of(new SchemaObject("HR", null, true, List.of("EMPLOYEES"))),
                new TableMappings(
                        null,
                        List.of(new TableMapping("HR", "EMPLOYEES", "hr", "emp"))));

        AppException ex = assertThrows(AppException.class, () -> validator.validate(tables));
        assertEquals(ErrorCode.PARAM_INVALID, ex.getCode());
        assertEquals("Mapped table is listed in excludeTables: HR.EMPLOYEES", ex.getMessage());
    }

    @Test
    void omittedMappingsResolveToOriginalNames() {
        TableSelection tables = selection(
                List.of(new SchemaObject("HR", List.of("EMPLOYEES"), null, null)),
                null);

        validator.validate(tables);
        assertEquals("HR.EMPLOYEES", validator.resolve(tables, "HR", "EMPLOYEES"));
    }

    private static TableSelection selection(List<SchemaObject> objects, TableMappings mappings) {
        return new TableSelection(objects, mappings);
    }
}
