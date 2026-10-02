package com.metaform.dxonboarding.adapter.out.nats;

import com.metaform.dxonboarding.domain.port.OnboardingEventPublisher;
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

/** The NATS connection and the publisher of the onboarding events, when {@code nats.enabled=true}. */
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

    @Bean
    public OnboardingEventPublisher onboardingEventPublisher(JetStream jetStream) {
        return new NatsOnboardingEventPublisher(jetStream, source());
    }

    /** The CloudEvents source: the pod's name, like the platform's EDC runtimes. */
    private static String source() {
        var hostname = System.getenv("HOSTNAME");
        if (hostname != null && !hostname.isBlank()) {
            return hostname;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "localhost";
        }
    }
}
