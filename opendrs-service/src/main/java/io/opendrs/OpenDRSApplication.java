package io.opendrs;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("io.opendrs.migration.mapper")
public class OpenDRSApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenDRSApplication.class, args);
    }
}
