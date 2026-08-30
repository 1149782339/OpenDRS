package io.opendrs.migration.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "opendrs.job.dispatch-enabled", havingValue = "true", matchIfMissing = true)
public class JobSchedulingConfig {
}
