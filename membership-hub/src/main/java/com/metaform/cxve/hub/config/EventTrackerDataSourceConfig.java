package com.metaform.cxve.hub.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Second, read-only connection pool pointing at the compliance tracker's {@code event_tracker}
 * database. The tracker owns that schema and is its only writer; the hub merely SELECTs from the
 * read-time views the tracker maintains ({@code participant_eventlog} / {@code participant_event}
 * — see compliance-tracker/store/tables.go), which is why this is a plain {@link JdbcClient} and
 * not a second JPA unit: no entities, no ddl, no write path.
 *
 * <p>The pool is deliberately NOT exposed as a {@code DataSource} bean: Boot's
 * DataSourceAutoConfiguration backs off as soon as ANY user-defined DataSource bean exists,
 * which would silently re-point the hub's whole JPA persistence — the membership records — at
 * the tracker database (read-only, wrong schema). Built inline and closed by this config, the
 * autoconfigured primary datasource stays exactly as it is.
 *
 * <p>Everything here is conditional on {@code eventtracker.datasource.url} so the hub still runs
 * (and its tests still run) without the tracker database.
 */
@Configuration
@ConditionalOnProperty(prefix = "eventtracker.datasource", name = "url")
public class EventTrackerDataSourceConfig implements DisposableBean {

    private HikariDataSource dataSource;

    @Bean
    public JdbcClient eventTrackerJdbcClient(
            @Value("${eventtracker.datasource.url}") String url,
            @Value("${eventtracker.datasource.username:event_tracker}") String username,
            @Value("${eventtracker.datasource.password:event_tracker}") String password) {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setReadOnly(true);
        // A UI polling the eventlog, not an OLTP path — two connections are plenty.
        dataSource.setMaximumPoolSize(2);
        dataSource.setPoolName("event-tracker");
        return JdbcClient.create(dataSource);
    }

    @Override
    public void destroy() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
