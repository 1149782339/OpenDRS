package io.opendrs.sink.ddl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DdlStatementsTest {

    @Test
    void dropsMysqlSessionStatementsKeepsCreate() {
        List<String> filtered = DdlStatements.executableForPostgres(List.of(
                "SET character_set_server=utf8mb4, collation_server=utf8mb4_0900_ai_ci",
                "USE inventory",
                "CREATE TABLE \"inventory\".\"customers\" (\"id\" int NOT NULL)",
                "/* engine=innodb */",
                "SELECT @@version"));
        assertThat(filtered).containsExactly("CREATE TABLE \"inventory\".\"customers\" (\"id\" int NOT NULL)");
    }

    @Test
    void treatsSetAsSessionOnly() {
        assertThat(DdlStatements.isMysqlSessionOnly(
                        "SET character_set_server=utf8mb4, collation_server=utf8mb4_0900_ai_ci"))
                .isTrue();
        assertThat(DdlStatements.isMysqlSessionOnly("CREATE TABLE t (id INT)")).isFalse();
    }
}
