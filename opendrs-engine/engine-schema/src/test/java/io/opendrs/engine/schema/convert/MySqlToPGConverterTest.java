package io.opendrs.engine.schema.convert;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.engine.schema.convert.converters.MySqlToPGConverter;
import io.opendrs.common.metadata.TableId;
import org.junit.jupiter.api.Test;

class MySqlToPGConverterTest {

    @Test
    void convertsAlterTableWithBareQualifiedName() {
        // Binlog DDL keeps whatever the client typed: `alter table test.test ...` has no backticks.
        ConversionResult result = identityConverter().convert("ALTER TABLE test.test ADD COLUMN col3 INT NULL");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String joined = String.join("\n", result.getStatements());
        assertThat(joined).contains("\"test\".\"test\"");
        assertThat(joined).contains("col3");
    }

    @Test
    void convertsAlterTableWithMixedQuoting() {
        ConversionResult result = identityConverter().convert("ALTER TABLE `test`.test ADD COLUMN `col3` INT NULL");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String joined = String.join("\n", result.getStatements());
        assertThat(joined).contains("\"test\".\"test\"");
        assertThat(joined).contains("\"col3\"");
    }

    @Test
    void dropsAlterTableThatOnlyResetsTheAutoIncrementCounter() {
        // MySQL writes `use test; ALTER TABLE `test`.`test` AUTO_INCREMENT = 11` to the binlog when a
        // client resets the counter. Every table option is commented out, so keeping the statement
        // would send PostgreSQL `ALTER TABLE "test"."test" /* AUTO_INCREMENT = 11 */` and it answers
        // "syntax error at end of input".
        ConversionResult result = identityConverter().convert("ALTER TABLE `test`.`test` AUTO_INCREMENT = 11");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        assertThat(result.getStatements()).isEmpty();
    }

    @Test
    void dropsAlterTableWithNoSpecificationAtAll() {
        ConversionResult result = identityConverter().convert("ALTER TABLE `test`.`test`");

        assertThat(result.getStatements()).isEmpty();
    }

    @Test
    void keepsAlterTableThatMixesATableOptionWithAColumnChange() {
        // The option becomes a comment, the column change still has to reach the target.
        ConversionResult result = identityConverter()
                .convert("ALTER TABLE `test`.`test` ENGINE=InnoDB, ADD COLUMN `col3` INT NULL");

        String joined = String.join("\n", result.getStatements());
        assertThat(joined).contains("ADD COLUMN");
        assertThat(joined).contains("\"col3\"");
        assertThat(joined).contains("/* ENGINE=InnoDB */");
    }

    @Test
    void appliesTheTableMappingToAlterAndDrop() {
        MySqlToPGConverter mapped = new MySqlToPGConverter(new ConversionConfiguration(
                tableId -> new TableId(null, "app", "orders"), column -> column));

        String alter = String.join("\n", mapped.convert("ALTER TABLE `shop`.`orders` ADD COLUMN `note` TEXT NULL")
                .getStatements());
        assertThat(alter).contains("\"app\".\"orders\"");
        assertThat(alter).doesNotContain("\"shop\"");

        String drop = String.join("\n", mapped.convert("DROP TABLE `shop`.`orders`").getStatements());
        assertThat(drop).contains("\"app\".\"orders\"");
    }

    private static MySqlToPGConverter identityConverter() {
        return new MySqlToPGConverter(
                new ConversionConfiguration(tableId -> tableId, column -> column));
    }

    @Test
    void convertsSimpleCreateTable() {
        MySqlToPGConverter converter = identityConverter();
        ConversionResult result = converter.convert(
                "CREATE TABLE `inventory`.`customers` ("
                        + "`id` INT NOT NULL, "
                        + "`name` VARCHAR(100) NOT NULL, "
                        + "PRIMARY KEY (`id`)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        assertThat(result.getStatements()).isNotEmpty();
        String joined = String.join("\n", result.getStatements());
        assertThat(joined.toLowerCase()).contains("create table");
        assertThat(joined).contains("customers");
        assertThat(joined).contains("\"inventory\"");
        assertThat(joined.toLowerCase()).doesNotContain("engine=innodb default");
        assertThat(joined).contains("/*");
        assertThat(joined).doesNotContain("IF NOT EXISTS");
    }

    @Test
    void convertsCreateDatabaseIntoCreateSchema() {
        ConversionResult result = identityConverter().convert(
                "CREATE DATABASE `shop` /*!40100 DEFAULT CHARACTER SET utf8mb4 */");

        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        String sql = String.join("\n", result.getStatements());
        assertThat(sql).contains("CREATE SCHEMA \"shop\"");
        assertThat(sql).doesNotContain("`");
        assertThat(sql).doesNotContain("IF NOT EXISTS");
        assertThat(sql).contains("/*");
    }

    @Test
    void writesTheMappedSchemaName() {
        MySqlToPGConverter converter = new MySqlToPGConverter(
                ConversionConfiguration.builder(tableId -> tableId, column -> column)
                        .schemaNamingStrategy(schema -> "pg_" + schema)
                        .build());

        String sql = String.join("\n", converter.convert("CREATE DATABASE `shop`").getStatements());

        assertThat(sql).contains("CREATE SCHEMA \"pg_shop\"");
    }

    @Test
    void addsIfNotExistsToSchemaTableAndIndex() {
        MySqlToPGConverter converter = new MySqlToPGConverter(
                ConversionConfiguration.builder(tableId -> tableId, column -> column)
                        .ifNotExists(true)
                        .build());

        String schema = String.join("\n", converter.convert("CREATE DATABASE `shop`").getStatements());
        assertThat(schema).contains("CREATE SCHEMA IF NOT EXISTS \"shop\"");

        String table = String.join("\n", converter.convert(
                "CREATE TABLE `shop`.`t` ("
                        + "`id` INT NOT NULL, "
                        + "PRIMARY KEY (`id`), "
                        + "KEY `idx_id` (`id`)"
                        + ")").getStatements());
        assertThat(table).contains("CREATE TABLE IF NOT EXISTS");
        assertThat(table).contains("CREATE INDEX IF NOT EXISTS");
    }

    private static final String ORDERS_DDL =
            "CREATE TABLE `shop`.`orders` ("
                    + "`id` INT NOT NULL, "
                    + "`code` VARCHAR(32) NOT NULL, "
                    + "`customer_id` INT NOT NULL, "
                    + "PRIMARY KEY (`id`), "
                    + "UNIQUE KEY `uk_code` (`code`), "
                    + "KEY `idx_customer` (`customer_id`), "
                    + "CONSTRAINT `fk_customer` FOREIGN KEY (`customer_id`) "
                    + "REFERENCES `shop`.`customers` (`id`)"
                    + ") ENGINE=InnoDB COMMENT='orders'";

    @Test
    void keepsEveryStructuralElementByDefault() {
        String joined = String.join("\n", identityConverter().convert(ORDERS_DDL).getStatements());

        assertThat(joined).contains("PRIMARY KEY");
        assertThat(joined).contains("UNIQUE");
        assertThat(joined).contains("CREATE INDEX");
        assertThat(joined).contains("FOREIGN KEY");
        assertThat(joined).contains("COMMENT ON TABLE");
    }

    @Test
    void dropsOnlyTheForeignKeysWhenThatIsTheOnlySwitchOff() {
        MySqlToPGConverter converter = new MySqlToPGConverter(
                ConversionConfiguration.builder(tableId -> tableId, column -> column)
                        .includeForeignKeys(false)
                        .build());

        String joined = String.join("\n", converter.convert(ORDERS_DDL).getStatements());

        assertThat(joined).doesNotContain("FOREIGN KEY");
        assertThat(joined).contains("PRIMARY KEY");
        assertThat(joined).contains("UNIQUE");
        assertThat(joined).contains("CREATE INDEX");
        assertThat(joined).contains("COMMENT ON TABLE");
    }

    @Test
    void dropsEverythingButTheTableWhenAllSwitchesAreOff() {
        MySqlToPGConverter converter = new MySqlToPGConverter(
                ConversionConfiguration.builder(tableId -> tableId, column -> column)
                        .includePrimaryKey(false)
                        .includeUniqueKeys(false)
                        .includeSecondaryIndexes(false)
                        .includeForeignKeys(false)
                        .includeComments(false)
                        .build());

        String joined = String.join("\n", converter.convert(ORDERS_DDL).getStatements());

        assertThat(joined).contains("CREATE TABLE");
        assertThat(joined).doesNotContain("PRIMARY KEY");
        assertThat(joined).doesNotContain("UNIQUE");
        assertThat(joined).doesNotContain("CREATE INDEX");
        assertThat(joined).doesNotContain("FOREIGN KEY");
        assertThat(joined).doesNotContain("COMMENT ON");
    }

    @Test
    void dropsColumnInlinePrimaryKeyAndUnique() {
        MySqlToPGConverter converter = new MySqlToPGConverter(
                ConversionConfiguration.builder(tableId -> tableId, column -> column)
                        .includePrimaryKey(false)
                        .includeUniqueKeys(false)
                        .build());

        String joined = String.join("\n", converter
                .convert("CREATE TABLE `shop`.`t` (`id` INT PRIMARY KEY, `code` VARCHAR(8) UNIQUE)")
                .getStatements());

        assertThat(joined).doesNotContain("PRIMARY KEY");
        assertThat(joined).doesNotContain("UNIQUE");
        assertThat(joined).contains("\"id\"");
        assertThat(joined).contains("\"code\"");
    }
}
