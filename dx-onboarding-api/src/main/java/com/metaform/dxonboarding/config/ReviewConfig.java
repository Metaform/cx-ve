package com.metaform.dxonboarding.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The review settings, and the worker automatic approvals run on — off the request thread, so a
 * submission is acknowledged as SUBMITTED before it is approved, as the TSP's API describes.
 */
@Configuration
@EnableConfigurationProperties(ReviewProperties.class)
public class ReviewConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService reviewExecutor() {
        var counter = new AtomicInteger();
        return Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "review-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}
