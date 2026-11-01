package com.procurepal_services.stock_bridge_api.push;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** A small pool of its own, so a slow push service never holds up email, or a request. */
@Configuration
class PushAsyncConfig {

    @Bean(name = "pushExecutor")
    Executor pushExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("push-");
        executor.initialize();
        return executor;
    }
}
