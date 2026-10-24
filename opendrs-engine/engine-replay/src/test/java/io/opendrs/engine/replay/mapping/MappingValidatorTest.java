package io.opendrs.engine.replay.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.opendrs.common.metadata.TableId;
import io.opendrs.engine.replay.exception.MappingException;
import io.opendrs.jdbc.metadata.Table;
import io.opendrs.jdbc.metadata.TableRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class MappingValidatorTest {

    private final MappingValidator validator = new MappingValidator();

    @Test
    void acceptsValidSelectionAndMappings() {
        TableSelection tables = tableLevel(
                List.of(table("HR", "EMPLOYEES"), table("HR", "DEPARTMENTS"), table("SCOTT", "EMP")),
                new TableMappings(
                        List.of(new SchemaMapping("SCOTT", "scott")),
                        List.of(new TableMapping("HR", "EMPLOYEES", "hr", "emp"))));

        validator.validate(tables);
        assertEquals("scott.EMP", validator.resolve(tables, "SCOTT", "EMP"));
        assertEquals("hr.emp", validator.resolve(tables, "HR", "EMPLOYEES"));
        assertEquals("HR.DEPARTMENTS", validator.resolve(tables, "HR", "DEPARTMENTS"));
    }

    @Test
    void rejectsMixingWholeSchemasAndTables() {
        TableSelection tables = new TableSelection(
                List.of("HR"), List.of(table("SCOTT", "EMP")), null, null, null);

        MappingException ex = assertThrows(MappingException.class, () -> validator.validate(tables));
        assertEquals(
                "tables must not mix schemas and tables: select whole schemas or individual tables,"
                        + " got schemas=[HR] tables=[SCOTT.EMP]",
                ex.getMessage());
    }

    @Test
    void acceptsEmptySelectionAsEverything() {
        TableSelection all = TableSelection.all();

        validator.validate(all);
        validator.validate(null);
    }

    @Test
    void rejectsDuplicateSchemaMappingSource() {
        TableSelection tables = schemaLevel(
                List.of("HR"),
                new TableMappings(
                        List.of(new SchemaMapping("HR", "hr"), new SchemaMapping("HR", "hr2")),
                        null));

        MappingException ex = assertThrows(MappingException.class, () -> validator.validate(tables));
        assertEquals("Duplicate schema mapping source: HR", ex.getMessage());
    }

    @Test
    void rejectsTwoSchemasMappingToSameTarget() {
        TableSelection tables = schemaLevel(
                List.of("HR", "SCOTT"),
                new TableMappings(
                        List.of(new SchemaMapping("HR", "hr"), new SchemaMapping("SCOTT", "hr")),
                        null));

        MappingException ex = assertThrows(MappingException.class, () -> validator.validate(tables));
        assertEquals("Two schemas map to the same target schema: hr", ex.getMessage());
    }

    @Test
    void rejectsDuplicateTableMappingSource() {
        TableSelection tables = tableLevel(
                List.of(table("HR", "EMPLOYEES")),
                new TableMappings(
                        null,
                        List.of(
                                new TableMapping("HR", "EMPLOYEES", "hr", "emp"),
                                new TableMapping("HR", "EMPLOYEES", "hr", "employees"))));

        MappingException ex = assertThrows(MappingException.class, () -> validator.validate(tables));
        assertEquals("Duplicate table mapping source: HR.EMPLOYEES", ex.getMessage());
    }

    @Test
    void rejectsTwoTablesMappingToSameTarget() {
        TableSelection tables = tableLevel(
                List.of(table("HR", "EMPLOYEES"), table("HR", "JOBS")),
                new TableMappings(
                        null,
                        List.of(
                                new TableMapping("HR", "EMPLOYEES", "hr", "emp"),
                                new TableMapping("HR", "JOBS", "hr", "emp"))));

        MappingException ex = assertThrows(MappingException.class, () -> validator.validate(tables));
        assertEquals("Two tables map to the same target: hr.emp", ex.getMessage());
    }

    @Test
    void rejectsCrossLayerSchemaConflict() {
        TableSelection tables = tableLevel(
                List.of(table("HR", "EMPLOYEES")),
                new TableMappings(
                        List.of(new SchemaMapping("HR", "hr")),
                        List.of(new TableMapping("HR", "EMPLOYEES", "other", "emp"))));

        assertThrows(MappingException.class, () -> validator.validate(tables));
    }

    @Test
    void allowsTableRenameWhenTargetSchemaMatchesSchemaMapping() {
        TableSelection tables = tableLevel(
                List.of(table("HR", "EMPLOYEES")),
                new TableMappings(
                        List.of(new SchemaMapping("HR", "hr")),
                        List.of(new TableMapping("HR", "EMPLOYEES", "hr", "emp"))));

        validator.validate(tables);
        assertEquals("hr.emp", validator.resolve(tables, "HR", "EMPLOYEES"));
    }

    @Test
    void rejectsMappedSchemaNotInSelection() {
        TableSelection tables = schemaLevel(
                List.of("HR"),
                new TableMappings(List.of(new SchemaMapping("SCOTT", "scott")), null));

        MappingException ex = assertThrows(MappingException.class, () -> validator.validate(tables));
        assertEquals("Schema mapping source is not in the selection: SCOTT", ex.getMessage());
    }

    @Test
    void rejectsMappedTableNotInExplicitList() {
        TableSelection tables = tableLevel(
                List.of(table("HR", "DEPARTMENTS")),
                new TableMappings(null, List.of(new TableMapping("HR", "EMPLOYEES", "hr", "emp"))));

        MappingException ex = assertThrows(MappingException.class, () -> validator.validate(tables));
        assertEquals("Mapped table is not in the selection: HR.EMPLOYEES", ex.getMessage());
    }

    @Test
    void acceptsMappedTableWhenWholeSchemaSelected() {
        TableSelection tables = schemaLevel(
                List.of("HR"),
                new TableMappings(null, List.of(new TableMapping("HR", "EMPLOYEES", "hr", "emp"))));

        validator.validate(tables);
    }

    @Test
    void acceptsMappedTableWhenEverythingSelected() {
        TableSelection tables = new TableSelection(
                null,
                null,
                null,
                null,
                new TableMappings(null, List.of(new TableMapping("HR", "EMPLOYEES", "hr", "emp"))));

        validator.validate(tables);
    }

    @Test
    void rejectsMappedTableThatIsExcluded() {
        TableSelection tables = new TableSelection(
                List.of("HR"),
                null,
                null,
                List.of(table("HR", "EMPLOYEES")),
                new TableMappings(null, List.of(new TableMapping("HR", "EMPLOYEES", "hr", "emp"))));

        MappingException ex = assertThrows(MappingException.class, () -> validator.validate(tables));
        assertEquals("Mapped table is listed in excludeTables: HR.EMPLOYEES", ex.getMessage());
    }

    @Test
    void rejectsMappedTableInExcludedSchema() {
        TableSelection tables = new TableSelection(
                null,
                null,
                List.of("HR"),
                null,
                new TableMappings(null, List.of(new TableMapping("HR", "EMPLOYEES", "hr", "emp"))));

        MappingException ex = assertThrows(MappingException.class, () -> validator.validate(tables));
        assertEquals("Mapped table is in an excluded schema: HR.EMPLOYEES", ex.getMessage());
    }

    @Test
    void omittedMappingsResolveToOriginalNames() {
        TableSelection tables = tableLevel(List.of(table("HR", "EMPLOYEES")), null);

        validator.validate(tables);
        assertEquals("HR.EMPLOYEES", validator.resolve(tables, "HR", "EMPLOYEES"));
        assertEquals(new TableRef("HR", "EMPLOYEES"), validator.resolveRef(tables, "HR", "EMPLOYEES"));
    }

    @Test
    void mapTargetsAppliesMappingsAndRejectsCollision() {
        TableSelection tables = tableLevel(
                List.of(table("HR", "EMPLOYEES"), table("HR", "DEPARTMENTS")),
                new TableMappings(
                        List.of(new SchemaMapping("HR", "hr")),
                        List.of(new TableMapping("HR", "EMPLOYEES", "hr", "emp"))));

        List<Table> targets = validator.mapTargets(
                tables,
                List.of(new Table(new TableRef("HR", "EMPLOYEES")), new Table(new TableRef("HR", "DEPARTMENTS"))));
        assertEquals(new TableRef("hr", "emp"), targets.get(0).ref());
        assertEquals(new TableRef("hr", "DEPARTMENTS"), targets.get(1).ref());

        TableSelection colliding = tableLevel(
                List.of(table("HR", "A"), table("HR", "B")),
                new TableMappings(
                        null,
                        List.of(
                                new TableMapping("HR", "A", "hr", "x"),
                                new TableMapping("HR", "B", "hr", "x"))));
        MappingException ex = assertThrows(
                MappingException.class,
                () -> validator.mapTargets(
                        colliding,
                        List.of(new Table(new TableRef("HR", "A")), new Table(new TableRef("HR", "B")))));
        assertEquals("Two tables map to the same target: hr.x", ex.getMessage());
    }

    private static TableSelection schemaLevel(List<String> schemas, TableMappings mappings) {
        return new TableSelection(schemas, null, null, null, mappings);
    }

    private static TableSelection tableLevel(List<TableId> tables, TableMappings mappings) {
        return new TableSelection(null, tables, null, null, mappings);
    }

    private static TableId table(String schema, String table) {
        return TableId.of(schema, table);
    }
}
