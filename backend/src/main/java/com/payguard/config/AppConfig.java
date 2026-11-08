package com.payguard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Clock;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
@EnableScheduling
public class AppConfig {

    /** Inject time instead of calling Instant.now() everywhere, so tests can control it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Thread pool for the gateway simulator's outgoing webhook HTTP calls. */
    @Bean(name = "webhookExecutor")
    Executor webhookExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("gw-webhook-");
        executor.initialize();
        return executor;
    }
}
