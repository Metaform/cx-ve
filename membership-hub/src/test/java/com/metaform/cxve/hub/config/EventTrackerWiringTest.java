package com.metaform.cxve.hub.config;

import com.metaform.cxve.hub.adapter.out.eventlog.EventlogRepository;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the read-only-membership incident: Boot's DataSourceAutoConfiguration
 * backs off as soon as any user-defined DataSource BEAN exists, so publishing the tracker pool
 * as a bean silently re-pointed the hub's whole JPA persistence at the (read-only!)
 * event_tracker database — every membership INSERT then failed with "cannot execute INSERT in a
 * read-only transaction". This loads the FULL context with the eventlog side enabled (primary
 * datasource swapped for in-memory H2) and pins the wiring: the one DataSource bean is the
 * autoconfigured primary, and the tracker access exists only as the qualified JdbcClient.
 */
@SpringBootTest(properties = {
        "eventtracker.datasource.url=jdbc:postgresql://unused:5432/event_tracker",
        // primary datasource: in-memory stand-in so the (non-test-profile) JPA machinery boots
        "spring.datasource.url=jdbc:h2:mem:wiring;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class EventTrackerWiringTest {

    @Autowired
    private DataSource primaryDataSource;

    @Autowired
    private EventlogRepository eventlogRepository;

    @Test
    void primaryDatasourceStaysAutoconfigured_andPointsAtTheMembershipDatabase() {
        // an unqualified DataSource injection must resolve to the autoconfigured primary — if
        // the tracker pool were a DataSource bean, autoconfiguration would have backed off and
        // this would be the (read-only) event_tracker pool instead
        assertThat(primaryDataSource).isInstanceOf(HikariDataSource.class);
        var hikari = (HikariDataSource) primaryDataSource;
        assertThat(hikari.getJdbcUrl()).contains("h2:mem:wiring");
        assertThat(hikari.isReadOnly()).isFalse();
        // the eventlog side is wired (its beans exist), just not as a DataSource bean
        assertThat(eventlogRepository).isNotNull();
    }
}
