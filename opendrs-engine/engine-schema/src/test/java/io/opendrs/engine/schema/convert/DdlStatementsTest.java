package io.opendrs.engine.schema.convert;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DdlStatementsTest {

    @Test
    void dropsMysqlSessionStatementsKeepsCreate() {
        List<String> filtered = DdlStatements.executableForPostgres(List.of(
                "SET character_set_server=utf8mb4, collation_server=utf8mb4_0900_ai_ci",
                "USE inventory",
                "DROP DATABASE IF EXISTS `inventory`",
                "CREATE DATABASE inventory",
                "CREATE SCHEMA inventory",
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

    @Test
    void mergeFoldsAlterPartitionByOntoPrecedingCreate() {
        List<String> merged = DdlStatements.mergeCreateTableWithAlterPartitionBy(List.of(
                "CREATE TABLE titles (emp_no INT NOT NULL, from_date DATE NOT NULL, PRIMARY KEY (emp_no, from_date))",
                "ALTER TABLE titles PARTITION BY RANGE COLUMNS (from_date) ("
                        + "PARTITION p01 VALUES LESS THAN ('1985-12-31'), "
                        + "PARTITION pmax VALUES LESS THAN (MAXVALUE))"));
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0)).contains("CREATE TABLE titles");
        assertThat(merged.get(0)).contains("PARTITION BY RANGE COLUMNS (from_date)");
        assertThat(merged.get(0)).doesNotContain("ALTER TABLE");
    }

    @Test
    void mergeLeavesIsolatedAlterPartitionBy() {
        List<String> merged = DdlStatements.mergeCreateTableWithAlterPartitionBy(List.of(
                "ALTER TABLE titles PARTITION BY RANGE COLUMNS (from_date) ("
                        + "PARTITION p01 VALUES LESS THAN ('1985-12-31'))"));
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0)).startsWith("ALTER TABLE");
    }

    @Test
    void mergeDoesNotFoldAddPartition() {
        List<String> merged = DdlStatements.mergeCreateTableWithAlterPartitionBy(List.of(
                "CREATE TABLE t (id INT, region CHAR(2))",
                "ALTER TABLE t ADD PARTITION (PARTITION us VALUES IN ('US'))"));
        assertThat(merged).hasSize(2);
        assertThat(merged.get(1)).contains("ADD PARTITION");
    }

    @Test
    void splitStatementsRespectsQuotes() {
        List<String> parts = DdlStatements.splitStatements(
                "CREATE TABLE t (note VARCHAR(10) DEFAULT 'a;b'); ALTER TABLE t PARTITION BY HASH (id) PARTITIONS 2");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).contains("DEFAULT 'a;b'");
        assertThat(parts.get(1)).startsWith("ALTER TABLE");
    }

    @Test
    void splitStatementsKeepsBeginEndRoutineBodyIntact() {
        String fn = "CREATE FUNCTION calc_tax(amount DECIMAL(10,2)) RETURNS DECIMAL(10,2) "
                + "BEGIN DECLARE tax DECIMAL(10,2) DEFAULT 0; "
                + "SET tax = amount * 0.1; "
                + "IF tax < 0 THEN SET tax = 0; END IF; "
                + "RETURN tax; END";
        List<String> parts = DdlStatements.splitStatements(fn + "; CREATE TABLE t (id INT)");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).startsWith("CREATE FUNCTION");
        assertThat(parts.get(0)).contains("DECLARE tax");
        assertThat(parts.get(0)).contains("END IF");
        assertThat(parts.get(0)).endsWith("END");
        assertThat(parts.get(1)).startsWith("CREATE TABLE");
    }
}
