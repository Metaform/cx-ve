package com.metaform.cxve.adapter.out.nats;

import com.metaform.cxve.domain.port.OnboardingEventPublisher;
import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the NATS connection and JetStream context when {@code nats.enabled=true}. Left disabled the
 * app starts with no broker dependency (local dev, tests, the current in-memory deployment).
 */
@Configuration
@EnableConfigurationProperties(NatsProperties.class)
@ConditionalOnProperty(prefix = "nats", name = "enabled", havingValue = "true")
public class NatsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NatsConfiguration.class);

    @Bean(destroyMethod = "close")
    public Connection natsConnection(NatsProperties properties) throws IOException, InterruptedException {
        var options = new Options.Builder()
                .server(properties.url())
                .maxReconnects(-1)
                .reconnectWait(Duration.ofSeconds(1))
                .pingInterval(Duration.ofSeconds(20))
                .maxPingsOut(5);
        if (properties.hasNkeyAuth()) {
            options.authHandler(new NKeyAuthHandler(Path.of(properties.nkeySeedPath())));
            log.info("Connecting to NATS at {} with NKey auth", properties.url());
        } else {
            log.info("Connecting to NATS at {} without authentication", properties.url());
        }
        return Nats.connect(options.build());
    }

    @Bean
    public JetStream jetStream(Connection connection) throws IOException {
        return connection.jetStream();
    }

    /**
     * Declared here rather than annotated as a component so the "is NATS on?" condition lives in
     * exactly one place — a @Component would be instantiated whenever the app is scanned and fail
     * to find a {@link JetStream} bean with NATS disabled.
     */
    @Bean
    public OnboardingEventPublisher onboardingEventPublisher(JetStream jetStream) {
        var source = resolveSource();
        log.info("Publishing onboarding events with CloudEvents source '{}'", source);
        return new NatsOnboardingEventPublisher(jetStream, source);
    }

    /**
     * The CloudEvents {@code source} for events this app emits: its hostname, matching the EDC
     * runtimes (whose events-nats bridge uses the injected {@code Hostname} service). Under
     * Kubernetes HOSTNAME is the pod name, which identifies the producing instance.
     */
    private static String resolveSource() {
        var fromEnv = System.getenv("HOSTNAME");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            // Never fail startup over a cosmetic attribute; "localhost" is what EDC's Hostname
            // service defaults to as well.
            log.warn("Could not resolve the local hostname for the CloudEvents source, using 'localhost'", e);
            return "localhost";
        }
    }
}
