package io.opendrs.engine.ddl;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.engine.ddl.converters.ConversionConfiguration;
import io.opendrs.engine.ddl.converters.ConversionResult;
import io.opendrs.engine.ddl.converters.ConversionStatus;
import io.opendrs.engine.ddl.converters.MySqlToPGConverter;
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
    }
}
