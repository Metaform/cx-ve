package com.metaform.dxonboarding.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The worker the decisions run on — off the request thread, so a submission returns at once. */
@Configuration
public class AsyncConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService decisionExecutor() {
        return Executors.newFixedThreadPool(2);
    }
}
