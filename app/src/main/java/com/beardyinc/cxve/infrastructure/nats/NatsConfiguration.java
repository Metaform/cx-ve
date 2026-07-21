package com.beardyinc.cxve.infrastructure.nats;

import io.nats.client.Connection;
import io.nats.client.JetStream;
import io.nats.client.Nats;
import io.nats.client.Options;
import java.io.IOException;
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
}
