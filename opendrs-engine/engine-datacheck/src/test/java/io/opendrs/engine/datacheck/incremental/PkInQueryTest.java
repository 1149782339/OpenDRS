package io.opendrs.engine.datacheck.incremental;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opendrs.engine.common.metadata.ColumnDefinition;
import io.opendrs.engine.common.metadata.TableDefinition;
import io.opendrs.engine.common.metadata.TableId;
import java.util.List;
import org.junit.jupiter.api.Test;

class PkInQueryTest {

    @Test
    void singleColumnInList() {
        TableDefinition definition = table("id");
        String sql = PkInQuery.selectSql(TableId.of("s", "t"), definition, "\"", 3);
        assertEquals(
                "SELECT \"id\", \"name\", \"a\", \"b\" FROM \"s\".\"t\" WHERE \"id\" IN (?,?,?)",
                sql);
    }

    @Test
    void compositeRowValueInList() {
        TableDefinition definition = table("a", "b");
        String sql = PkInQuery.selectSql(TableId.of("s", "t"), definition, "\"", 2);
        assertTrue(sql.contains("WHERE (\"a\", \"b\") IN ((?,?), (?,?))"));
        assertTrue(sql.startsWith("SELECT "));
    }

    @Test
    void appendInPredicateIsReusable() {
        StringBuilder sql = new StringBuilder();
        PkInQuery.appendInPredicate(sql, List.of("id"), "`", 1);
        assertEquals("`id` IN (?)", sql.toString());
    }

    private static TableDefinition table(String... pk) {
        List<ColumnDefinition> columns = List.of(
                column("id"), column("name"), column("a"), column("b"));
        return TableDefinition.builder()
                .tableId(TableId.of("s", "t"))
                .columnDefinitions(columns)
                .primaryKeyColumNames(List.of(pk))
                .build();
    }

    private static ColumnDefinition column(String name) {
        return ColumnDefinition.builder().name(name).typeName("INT").build();
    }
}
