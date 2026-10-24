package io.opendrs.engine.precheck.fact;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MysqlGrantsTest {

    @Test
    void globalGrantCoversEverySchema() {
        MysqlGrants grants = MysqlGrants.parse(List.of(
                "GRANT SELECT, RELOAD ON *.* TO `cdc`@`%`"));

        assertTrue(grants.has("SELECT", "hr"));
        assertTrue(grants.has("RELOAD", "anything"));
        assertTrue(grants.hasGlobally("SELECT"));
    }

    @Test
    void schemaGrantOnlyCoversThatSchema() {
        MysqlGrants grants = MysqlGrants.parse(List.of(
                "GRANT SELECT ON `hr`.* TO `cdc`@`%`"));

        assertTrue(grants.has("SELECT", "hr"));
        assertFalse(grants.has("SELECT", "finance"));
        assertFalse(grants.hasGlobally("SELECT"));
        assertFalse(grants.has("RELOAD", "hr"));
    }

    @Test
    void tableGrantStillAllowsReadingThatTable() {
        MysqlGrants grants = MysqlGrants.parse(List.of(
                "GRANT SELECT ON `hr`.`emp` TO `cdc`@`%`"));

        assertTrue(grants.has("SELECT", "hr"));
    }

    @Test
    void allPrivilegesBecomesAWildcard() {
        MysqlGrants grants = MysqlGrants.parse(List.of(
                "GRANT ALL PRIVILEGES ON *.* TO `root`@`localhost` WITH GRANT OPTION"));

        assertTrue(grants.has("REPLICATION SLAVE", "hr"));
        assertTrue(grants.hasGlobally("ANY FUTURE PRIVILEGE"));
    }

    @Test
    void unrelatedStatementsAreIgnored() {
        MysqlGrants grants = MysqlGrants.parse(List.of(
                "GRANT USAGE ON *.* TO `cdc`@`%`",
                "GRANT `r_read`@`%` TO `cdc`@`%`"));

        assertFalse(grants.has("SELECT", "hr"));
        assertFalse(grants.hasGlobally("RELOAD"));
    }
}
