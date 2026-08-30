package io.opendrs.migration.job;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class JobExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor taskJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor() {
            @Override
            public void stop() {
                if (getThreadPoolExecutor() != null) {
                    getThreadPoolExecutor().shutdownNow();
                }
            }

            @Override
            public boolean isRunning() {
                // Incremental stubs block on a latch; do not hold Spring shutdown for 30s.
                return false;
            }
        };
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("opendrs-job-");
        executor.setThreadFactory(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("opendrs-job-" + thread.threadId());
            thread.setDaemon(true);
            return thread;
        });
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(1);
        executor.initialize();
        return executor;
    }
}
