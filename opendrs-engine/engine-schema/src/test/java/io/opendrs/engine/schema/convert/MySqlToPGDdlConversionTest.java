package io.opendrs.engine.schema.convert;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.engine.schema.convert.converters.MySqlToPGConverter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Source SQL → expected PostgreSQL SQL assertions for DDL conversion, covering both previously
 * implemented forms (CREATE/DROP/column ALTER/VIEW) and ALTER structure + RENAME/TRUNCATE.
 */
class MySqlToPGDdlConversionTest {

    private static MySqlToPGConverter identityConverter() {
        return new MySqlToPGConverter(
                new ConversionConfiguration(tableId -> tableId, column -> column));
    }

    private static void assertConverts(String sourceSql, String... expectedStatements) {
        ConversionResult result = identityConverter().convert(sourceSql);
        assertThat(result.getStatus())
                .as("status for: %s", sourceSql)
                .isEqualTo(ConversionStatus.SUCCEEDED);
        assertThat(result.getStatements())
                .as("statements for: %s", sourceSql)
                .containsExactly(expectedStatements);
    }

    // --- previously implemented DDL ---

    @Test
    void createDatabaseBecomesCreateSchema() {
        assertConverts("CREATE DATABASE `shop`", "CREATE SCHEMA \"shop\"");
    }

    @Test
    void createTableMapsTypesQuotesAndCommentsEngine() {
        assertConverts(
                "CREATE TABLE `shop`.`t` (`id` INT NOT NULL, PRIMARY KEY (`id`)) ENGINE=InnoDB",
                "CREATE TABLE \"shop\".\"t\" (\"id\" int NOT NULL, PRIMARY KEY (\"id\")) /* ENGINE=InnoDB */");
    }

    @Test
    void createTableEmitsIndexUniqueForeignKeyAndCommentFollowUps() {
        ConversionResult result = identityConverter().convert(
                "CREATE TABLE `shop`.`orders` ("
                        + "`id` INT NOT NULL, "
                        + "`code` VARCHAR(32) NOT NULL, "
                        + "`customer_id` INT NOT NULL, "
                        + "PRIMARY KEY (`id`), "
                        + "UNIQUE KEY `uk_code` (`code`), "
                        + "KEY `idx_customer` (`customer_id`), "
                        + "CONSTRAINT `fk_customer` FOREIGN KEY (`customer_id`) "
                        + "REFERENCES `shop`.`customers` (`id`)"
                        + ") ENGINE=InnoDB COMMENT='orders'");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        List<String> stmts = result.getStatements();
        assertThat(stmts.get(0)).isEqualTo(
                "CREATE TABLE \"shop\".\"orders\" (\"id\" int NOT NULL, \"code\" VARCHAR(32) NOT NULL, "
                        + "\"customer_id\" int NOT NULL, PRIMARY KEY (\"id\")   ) /* ENGINE=InnoDB */");
        assertThat(stmts).contains(
                "COMMENT ON TABLE \"orders\" is 'orders'",
                "ALTER TABLE orders ADD CONSTRAINT \"orders_uk_code\" UNIQUE (\"code\")",
                "CREATE INDEX \"orders_idx_customer\" ON \"shop\".\"orders\"(\"customer_id\")",
                "ALTER TABLE \"shop\".\"orders\" ADD CONSTRAINT \"fk_customer\" FOREIGN KEY(\"customer_id\") "
                        + "REFERENCES \"shop\".\"customers\"(\"id\")");
    }

    @Test
    void createViewRewritesIdentifiers() {
        ConversionResult result = identityConverter().convert(
                "CREATE VIEW `shop`.`v` AS SELECT `id` FROM `shop`.`t`");
        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        assertThat(result.getStatements()).containsExactly(
                "CREATE VIEW \"shop\".\"v\" AS SELECT \"id\" FROM \"shop\".\"t\"");
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("functions are not translated"));
    }

    @Test
    void dropTableAddsCascadeAndQuotes() {
        assertConverts("DROP TABLE `shop`.`t`", "DROP TABLE \"shop\".\"t\" cascade");
    }

    @Test
    void alterAddColumnQuotesIdentifiers() {
        assertConverts(
                "ALTER TABLE `shop`.`t` ADD COLUMN `note` TEXT NULL",
                "ALTER TABLE \"shop\".\"t\" ADD COLUMN \"note\" TEXT NULL");
    }

    @Test
    void alterAddColumnDropsFirstPosition() {
        ConversionResult result = identityConverter().convert(
                "ALTER TABLE `shop`.`t` ADD COLUMN `a` INT NULL FIRST");
        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        assertThat(result.getStatements()).containsExactly(
                "ALTER TABLE \"shop\".\"t\" ADD COLUMN \"a\" int NULL");
        assertThat(result.getInfos()).anyMatch(m -> m.contains("FIRST"));
    }

    @Test
    void alterModifyColumnBecomesAlterType() {
        assertConverts(
                "ALTER TABLE `shop`.`t` MODIFY COLUMN `id` BIGINT NOT NULL",
                "ALTER TABLE \"shop\".\"t\" ALTER COLUMN \"id\" TYPE BIGINT NOT NULL");
    }

    @Test
    void alterChangeColumnEmitsRenameThenTypeChange() {
        assertConverts(
                "ALTER TABLE `shop`.`t` CHANGE COLUMN `note` `remark` VARCHAR(64) NULL",
                "ALTER TABLE \"shop\".\"t\" RENAME COLUMN \"note\" TO \"remark\"",
                "ALTER TABLE \"shop\".\"t\" ALTER COLUMN \"remark\" TYPE VARCHAR(64) NULL");
    }

    @Test
    void alterDropColumnQuotesIdentifier() {
        assertConverts(
                "ALTER TABLE `shop`.`t` DROP COLUMN `remark`",
                "ALTER TABLE \"shop\".\"t\" DROP COLUMN \"remark\"");
    }

    @Test
    void alterOnlyAutoIncrementIsDropped() {
        ConversionResult result = identityConverter().convert(
                "ALTER TABLE `test`.`test` AUTO_INCREMENT = 11");
        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        assertThat(result.getStatements()).isEmpty();
    }

    // --- ALTER structure + RENAME TABLE + TRUNCATE ---

    @Test
    void alterAddIndexBecomesCreateIndex() {
        assertConverts(
                "ALTER TABLE `shop`.`orders` ADD INDEX `idx_customer` (`customer_id`)",
                "CREATE INDEX \"orders_idx_customer\" ON \"shop\".\"orders\"(\"customer_id\")");
    }

    @Test
    void alterAddPrimaryKeyBecomesAddConstraint() {
        assertConverts(
                "ALTER TABLE `shop`.`orders` ADD PRIMARY KEY (`id`)",
                "ALTER TABLE \"shop\".\"orders\" ADD CONSTRAINT \"orders_pkey\" PRIMARY KEY (\"id\")");
    }

    @Test
    void alterAddUniqueKeyBecomesAddConstraint() {
        assertConverts(
                "ALTER TABLE `shop`.`orders` ADD UNIQUE KEY `uk_code` (`code`)",
                "ALTER TABLE \"shop\".\"orders\" ADD CONSTRAINT \"orders_uk_code\" UNIQUE (\"code\")");
    }

    @Test
    void alterAddForeignKeyKeepsOnDelete() {
        assertConverts(
                "ALTER TABLE `shop`.`orders` ADD CONSTRAINT `fk_customer` FOREIGN KEY (`customer_id`) "
                        + "REFERENCES `shop`.`customers` (`id`) ON DELETE CASCADE",
                "ALTER TABLE \"shop\".\"orders\" ADD CONSTRAINT \"fk_customer\" FOREIGN KEY(\"customer_id\") "
                        + "REFERENCES \"shop\".\"customers\"(\"id\") ON DELETE CASCADE");
    }

    @Test
    void alterAddCheckRewritesBackticks() {
        assertConverts(
                "ALTER TABLE `shop`.`t` ADD CONSTRAINT `chk_pos` CHECK (`id` > 0)",
                "ALTER TABLE \"shop\".\"t\" ADD CONSTRAINT \"chk_pos\" CHECK (\"id\" > 0)");
    }

    @Test
    void alterDropIndexBecomesDropIndex() {
        assertConverts(
                "ALTER TABLE `shop`.`orders` DROP INDEX `idx_customer`",
                "DROP INDEX \"orders_idx_customer\"");
    }

    @Test
    void alterDropPrimaryKeyUsesTablePkeyHeuristic() {
        ConversionResult result = identityConverter().convert(
                "ALTER TABLE `shop`.`orders` DROP PRIMARY KEY");
        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        assertThat(result.getStatements()).containsExactly(
                "ALTER TABLE \"shop\".\"orders\" DROP CONSTRAINT \"orders_pkey\"");
        assertThat(result.getInfos()).anyMatch(m -> m.contains("orders_pkey"));
    }

    @Test
    void alterDropForeignKeyBecomesDropConstraint() {
        assertConverts(
                "ALTER TABLE `shop`.`orders` DROP FOREIGN KEY `fk_customer`",
                "ALTER TABLE \"shop\".\"orders\" DROP CONSTRAINT \"fk_customer\"");
    }

    @Test
    void alterDropCheckBecomesDropConstraint() {
        assertConverts(
                "ALTER TABLE `shop`.`t` DROP CHECK `chk_pos`",
                "ALTER TABLE \"shop\".\"t\" DROP CONSTRAINT \"chk_pos\"");
    }

    @Test
    void alterRenameTable() {
        assertConverts(
                "ALTER TABLE `shop`.`orders` RENAME TO `order_archive`",
                "ALTER TABLE \"shop\".\"orders\" RENAME TO \"order_archive\"");
    }

    @Test
    void alterRenameColumn() {
        assertConverts(
                "ALTER TABLE `shop`.`orders` RENAME COLUMN `code` TO `order_code`",
                "ALTER TABLE \"shop\".\"orders\" RENAME COLUMN \"code\" TO \"order_code\"");
    }

    @Test
    void alterRenameIndexBecomesAlterIndex() {
        assertConverts(
                "ALTER TABLE `shop`.`orders` RENAME INDEX `idx_customer` TO `idx_cust`",
                "ALTER INDEX \"orders_idx_customer\" RENAME TO \"orders_idx_cust\"");
    }

    @Test
    void alterColumnSetAndDropDefault() {
        assertConverts(
                "ALTER TABLE `shop`.`t` ALTER COLUMN `c` SET DEFAULT 1",
                "ALTER TABLE \"shop\".\"t\" ALTER COLUMN \"c\" SET DEFAULT 1");
        assertConverts(
                "ALTER TABLE `shop`.`t` ALTER COLUMN `c` DROP DEFAULT",
                "ALTER TABLE \"shop\".\"t\" ALTER COLUMN \"c\" DROP DEFAULT");
    }

    @Test
    void alterAddColumnAndIndexSplitsStatements() {
        ConversionResult result = identityConverter().convert(
                "ALTER TABLE `shop`.`t` ADD COLUMN `a` INT NULL, ADD INDEX `idx_a` (`a`)");
        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        assertThat(result.getStatements()).containsExactly(
                "ALTER TABLE \"shop\".\"t\" ADD COLUMN \"a\" int NULL",
                "CREATE INDEX \"t_idx_a\" ON \"shop\".\"t\"(\"a\")");
    }

    @Test
    void renameTableBecomesAlterRename() {
        assertConverts(
                "RENAME TABLE `shop`.`a` TO `shop`.`b`",
                "ALTER TABLE \"shop\".\"a\" RENAME TO \"b\"");
    }

    @Test
    void renameTableMultipleClauses() {
        assertConverts(
                "RENAME TABLE `shop`.`a` TO `shop`.`b`, `shop`.`c` TO `d`",
                "ALTER TABLE \"shop\".\"a\" RENAME TO \"b\"",
                "ALTER TABLE \"shop\".\"c\" RENAME TO \"d\"");
    }

    @Test
    void truncateTableQuotesIdentifiers() {
        assertConverts(
                "TRUNCATE TABLE `shop`.`orders`",
                "TRUNCATE TABLE \"shop\".\"orders\"");
    }

    @Test
    void truncateWithoutTableKeywordInsertsTable() {
        assertConverts(
                "TRUNCATE `shop`.`orders`",
                "TRUNCATE TABLE \"shop\".\"orders\"");
    }

    // --- PARTITION BY (P0) ---

    @Test
    void createTableRangeColumnsEmitsPartitionOfChildren() {
        assertConverts(
                "CREATE TABLE `shop`.`orders` ("
                        + "`id` INT NOT NULL, "
                        + "`order_date` DATE NOT NULL, "
                        + "PRIMARY KEY (`id`, `order_date`)"
                        + ") ENGINE=InnoDB "
                        + "PARTITION BY RANGE COLUMNS (`order_date`) ("
                        + "PARTITION `p2023` VALUES LESS THAN ('2024-01-01'), "
                        + "PARTITION `p2024` VALUES LESS THAN ('2025-01-01'), "
                        + "PARTITION `pmax` VALUES LESS THAN (MAXVALUE)"
                        + ")",
                "CREATE TABLE \"shop\".\"orders\" (\"id\" int NOT NULL, \"order_date\" DATE NOT NULL, "
                        + "PRIMARY KEY (\"id\", \"order_date\")) /* ENGINE=InnoDB */ "
                        + "PARTITION BY RANGE (\"order_date\")",
                "CREATE TABLE \"shop\".\"orders_p2023\" PARTITION OF \"shop\".\"orders\" "
                        + "FOR VALUES FROM (MINVALUE) TO ('2024-01-01')",
                "CREATE TABLE \"shop\".\"orders_p2024\" PARTITION OF \"shop\".\"orders\" "
                        + "FOR VALUES FROM ('2024-01-01') TO ('2025-01-01')",
                "CREATE TABLE \"shop\".\"orders_pmax\" PARTITION OF \"shop\".\"orders\" "
                        + "FOR VALUES FROM ('2025-01-01') TO (MAXVALUE)");
    }

    @Test
    void createTableListColumnsEmitsPartitionOfChildren() {
        assertConverts(
                "CREATE TABLE `shop`.`t` ("
                        + "`id` INT NOT NULL, "
                        + "`region` VARCHAR(8) NOT NULL, "
                        + "PRIMARY KEY (`id`, `region`)"
                        + ") "
                        + "PARTITION BY LIST COLUMNS (`region`) ("
                        + "PARTITION `asia` VALUES IN ('CN','JP'), "
                        + "PARTITION `eu` VALUES IN ('DE','FR')"
                        + ")",
                "CREATE TABLE \"shop\".\"t\" (\"id\" int NOT NULL, \"region\" VARCHAR(8) NOT NULL, "
                        + "PRIMARY KEY (\"id\", \"region\")) PARTITION BY LIST (\"region\")",
                "CREATE TABLE \"shop\".\"t_asia\" PARTITION OF \"shop\".\"t\" "
                        + "FOR VALUES IN ('CN', 'JP')",
                "CREATE TABLE \"shop\".\"t_eu\" PARTITION OF \"shop\".\"t\" "
                        + "FOR VALUES IN ('DE', 'FR')");
    }

    @Test
    void createTableHashPartitionsEmitsModulusRemainderAndWarns() {
        ConversionResult result = identityConverter().convert(
                "CREATE TABLE `shop`.`t` (`id` INT NOT NULL, PRIMARY KEY (`id`)) "
                        + "PARTITION BY HASH (`id`) PARTITIONS 4");
        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        assertThat(result.getStatements()).containsExactly(
                "CREATE TABLE \"shop\".\"t\" (\"id\" int NOT NULL, PRIMARY KEY (\"id\")) "
                        + "PARTITION BY HASH (\"id\")",
                "CREATE TABLE \"shop\".\"t_p0\" PARTITION OF \"shop\".\"t\" "
                        + "FOR VALUES WITH (MODULUS 4, REMAINDER 0)",
                "CREATE TABLE \"shop\".\"t_p1\" PARTITION OF \"shop\".\"t\" "
                        + "FOR VALUES WITH (MODULUS 4, REMAINDER 1)",
                "CREATE TABLE \"shop\".\"t_p2\" PARTITION OF \"shop\".\"t\" "
                        + "FOR VALUES WITH (MODULUS 4, REMAINDER 2)",
                "CREATE TABLE \"shop\".\"t_p3\" PARTITION OF \"shop\".\"t\" "
                        + "FOR VALUES WITH (MODULUS 4, REMAINDER 3)");
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("HASH"));
    }

    @Test
    void createTableWithSubpartitionStripsPartitioning() {
        ConversionResult result = identityConverter().convert(
                "CREATE TABLE `shop`.`t` (`id` INT NOT NULL, `d` DATE NOT NULL, "
                        + "PRIMARY KEY (`id`, `d`)) "
                        + "PARTITION BY RANGE COLUMNS (`d`) "
                        + "SUBPARTITION BY HASH (`id`) SUBPARTITIONS 2 ("
                        + "PARTITION `p0` VALUES LESS THAN ('2024-01-01')"
                        + ")");
        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        assertThat(result.getStatements()).hasSize(1);
        assertThat(result.getStatements().get(0))
                .startsWith("CREATE TABLE \"shop\".\"t\"")
                .doesNotContain("PARTITION BY");
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("SUBPARTITION"));
    }

    @Test
    void createTableRangeYearExpressionStripsPartitioning() {
        ConversionResult result = identityConverter().convert(
                "CREATE TABLE `shop`.`t` (`id` INT NOT NULL, `order_date` DATE NOT NULL, "
                        + "PRIMARY KEY (`id`, `order_date`)) "
                        + "PARTITION BY RANGE (YEAR(`order_date`)) ("
                        + "PARTITION `p2023` VALUES LESS THAN (2024), "
                        + "PARTITION `pmax` VALUES LESS THAN MAXVALUE"
                        + ")");
        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        assertThat(result.getStatements()).hasSize(1);
        assertThat(result.getStatements().get(0)).doesNotContain("PARTITION BY");
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("bare column"));
    }

    @Test
    void createTableKeyPartitioningStrips() {
        ConversionResult result = identityConverter().convert(
                "CREATE TABLE `shop`.`t` (`id` INT NOT NULL, PRIMARY KEY (`id`)) "
                        + "PARTITION BY KEY (`id`) PARTITIONS 4");
        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        assertThat(result.getStatements()).hasSize(1);
        assertThat(result.getStatements().get(0)).doesNotContain("PARTITION BY");
        assertThat(result.getWarnings()).anyMatch(m -> m.contains("KEY"));
    }

    @Test
    void alterAddRangePartitionIsDropped() {
        ConversionResult result = identityConverter().convert(
                "ALTER TABLE `shop`.`t` ADD PARTITION (PARTITION `p5` VALUES LESS THAN (2026))");
        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        assertThat(result.getStatements()).isEmpty();
        assertThat(result.getInfos()).anyMatch(m -> m.contains("partition clause dropped"));
    }

    @Test
    void alterAddListPartitionBecomesCreatePartitionOf() {
        assertConverts(
                "ALTER TABLE `shop`.`t` ADD PARTITION ("
                        + "PARTITION `us` VALUES IN ('US','CA'), "
                        + "PARTITION `uk` VALUES IN ('UK')"
                        + ")",
                "CREATE TABLE \"shop\".\"t_us\" PARTITION OF \"shop\".\"t\" "
                        + "FOR VALUES IN ('US', 'CA')",
                "CREATE TABLE \"shop\".\"t_uk\" PARTITION OF \"shop\".\"t\" "
                        + "FOR VALUES IN ('UK')");
    }

    @Test
    void alterDropPartitionBecomesDropTableChildren() {
        assertConverts(
                "ALTER TABLE `shop`.`orders` DROP PARTITION `p2023`, `p2024`",
                "DROP TABLE \"shop\".\"orders_p2023\"",
                "DROP TABLE \"shop\".\"orders_p2024\"");
    }

    @Test
    void alterTruncatePartitionBecomesTruncateTableChildren() {
        assertConverts(
                "ALTER TABLE `shop`.`orders` TRUNCATE PARTITION `p2023`, `pmax`",
                "TRUNCATE TABLE \"shop\".\"orders_p2023\"",
                "TRUNCATE TABLE \"shop\".\"orders_pmax\"");
    }

    @Test
    void alterTruncatePartitionAllIsDropped() {
        ConversionResult result = identityConverter().convert(
                "ALTER TABLE `shop`.`orders` TRUNCATE PARTITION ALL");
        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        assertThat(result.getStatements()).isEmpty();
        assertThat(result.getInfos()).anyMatch(m -> m.contains("TRUNCATE PARTITION ALL"));
    }

    @Test
    void alterTrailingPartitionByIsDropped() {
        ConversionResult result = identityConverter().convert(
                "ALTER TABLE `shop`.`t` PARTITION BY HASH (`id`) PARTITIONS 4");
        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        assertThat(result.getStatements()).isEmpty();
        assertThat(result.getInfos()).anyMatch(m -> m.contains("PARTITION BY"));
    }

    @Test
    void createTablePartitionChildrenPrecedeIndexFollowUps() {
        ConversionResult result = identityConverter().convert(
                "CREATE TABLE `shop`.`orders` ("
                        + "`id` INT NOT NULL, "
                        + "`order_date` DATE NOT NULL, "
                        + "`customer_id` INT NOT NULL, "
                        + "PRIMARY KEY (`id`, `order_date`), "
                        + "KEY `idx_customer` (`customer_id`)"
                        + ") "
                        + "PARTITION BY RANGE COLUMNS (`order_date`) ("
                        + "PARTITION `p2024` VALUES LESS THAN ('2025-01-01'), "
                        + "PARTITION `pmax` VALUES LESS THAN (MAXVALUE)"
                        + ")");
        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        List<String> stmts = result.getStatements();
        assertThat(stmts.get(0)).contains("PARTITION BY RANGE (\"order_date\")");
        assertThat(stmts.get(1)).startsWith("CREATE TABLE \"shop\".\"orders_p2024\" PARTITION OF");
        assertThat(stmts.get(2)).startsWith("CREATE TABLE \"shop\".\"orders_pmax\" PARTITION OF");
        int firstPartitionOf = indexOfPrefix(stmts, "CREATE TABLE \"shop\".\"orders_p");
        int indexStmt = indexOfPrefix(stmts, "CREATE INDEX");
        assertThat(firstPartitionOf).isLessThan(indexStmt);
    }

    private static int indexOfPrefix(List<String> stmts, String prefix) {
        for (int i = 0; i < stmts.size(); i++) {
            if (stmts.get(i).startsWith(prefix)) {
                return i;
            }
        }
        throw new AssertionError("no statement starting with: " + prefix + " in " + stmts);
    }
}
