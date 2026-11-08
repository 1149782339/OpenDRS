package io.opendrs.datacheck;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Data-check service entry point. Scans {@code io.opendrs} so the shared API envelope, error
 * handler and i18n configuration from {@code opendrs-common} are picked up.
 */
@SpringBootApplication(scanBasePackages = "io.opendrs")
public class DataCheckApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataCheckApplication.class, args);
    }
}
