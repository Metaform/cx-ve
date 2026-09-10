package com.metaform.cxve.hub.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Second, read-only datasource pointing at the compliance tracker's {@code event_tracker}
 * database. The tracker owns that schema and is its only writer; the hub merely SELECTs from the
 * read-time views the tracker maintains ({@code participant_eventlog} / {@code participant_event}
 * — see compliance-tracker/store/tables.go), which is why this is a plain {@link JdbcClient} and
 * not a second JPA unit: no entities, no ddl, no write path.
 *
 * <p>Everything here is conditional on {@code eventtracker.datasource.url} so the hub still runs
 * (and its tests still run) without the tracker database. The beans are qualified and never
 * primary — Boot's autoconfigured primary datasource (membership persistence) is untouched.
 */
@Configuration
@ConditionalOnProperty(prefix = "eventtracker.datasource", name = "url")
public class EventTrackerDataSourceConfig {

    @Bean
    public DataSource eventTrackerDataSource(
            @Value("${eventtracker.datasource.url}") String url,
            @Value("${eventtracker.datasource.username:event_tracker}") String username,
            @Value("${eventtracker.datasource.password:event_tracker}") String password) {
        var dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setReadOnly(true);
        // A UI polling the eventlog, not an OLTP path — two connections are plenty.
        dataSource.setMaximumPoolSize(2);
        dataSource.setPoolName("event-tracker");
        return dataSource;
    }

    @Bean
    public JdbcClient eventTrackerJdbcClient(@Qualifier("eventTrackerDataSource") DataSource eventTrackerDataSource) {
        return JdbcClient.create(eventTrackerDataSource);
    }
}
