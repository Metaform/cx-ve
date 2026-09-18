package com.metaform.cxve.hub.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The pool a hosted member's onboarding runs on — deploying its EDC resources and then submitting
 * its registration. It must not run on the request thread: deployment takes minutes, while the
 * caller expects its membership record back immediately. Two threads bound the concurrency; each
 * membership is started exactly once because only its creating call ever hands it to this pool.
 */
@Configuration
public class AsyncConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService provisioningExecutor() {
        var counter = new AtomicInteger();
        return Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "provisioning-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}
