package io.opendrs.sink.ddl;

import static org.assertj.core.api.Assertions.assertThat;

import io.opendrs.sink.ddl.converters.ConversionConfiguration;
import io.opendrs.sink.ddl.converters.ConversionResult;
import io.opendrs.sink.ddl.converters.ConversionStatus;
import io.opendrs.sink.ddl.converters.MySqlToPGConverter;
import io.opendrs.sink.naming.DefaultColumnNamingStrategy;
import io.opendrs.sink.naming.DefaultTableNamingStrategy;
import org.junit.jupiter.api.Test;

class MySqlToPGConverterTest {

    @Test
    void convertsSimpleCreateTable() {
        MySqlToPGConverter converter = new MySqlToPGConverter(
                new ConversionConfiguration(new DefaultTableNamingStrategy(), new DefaultColumnNamingStrategy()));
        ConversionResult result = converter.convert(
                "CREATE TABLE `inventory`.`customers` ("
                        + "`id` INT NOT NULL, "
                        + "`name` VARCHAR(100) NOT NULL, "
                        + "PRIMARY KEY (`id`)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        assertThat(result.getStatus()).isEqualTo(ConversionStatus.SUCCEEDED);
        assertThat(result.getStatements()).isNotEmpty();
        String joined = String.join("\n", result.getStatements()).toLowerCase();
        assertThat(joined).contains("create table");
        assertThat(joined).contains("customers");
        assertThat(joined).doesNotContain("engine=innodb");
    }
}
