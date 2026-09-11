package com.metaform.cxve.hub.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The pool EDC provisioning runs on. Provisioning is triggered by the CONFIRMED status callback
 * but must not run ON the callback thread — the Onboarding API delivers callbacks fire-and-forget
 * with no retry, so the handler has to answer immediately rather than block for the Tenant
 * Manager round-trips. Two threads bound the concurrency; the CONFIRMED→PROVISIONING claim in
 * the service (an optimistic-lock compare-and-swap) is what guarantees each membership is
 * provisioned at most once, regardless of pool size or replica count.
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
