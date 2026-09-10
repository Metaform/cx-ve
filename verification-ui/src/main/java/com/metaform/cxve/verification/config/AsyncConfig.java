package com.metaform.cxve.verification.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The pool verification runs execute on. A run is one long-lived, mostly-sleeping thread (it
 * polls the platform for minutes); two of them bound the concurrency deliberately — the platform
 * under verification is shared, and stampeding it defeats the purpose.
 */
@Configuration
public class AsyncConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService runExecutor() {
        var counter = new AtomicInteger();
        return Executors.newFixedThreadPool(2, runnable -> {
            var thread = new Thread(runnable, "verification-run-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }
}
