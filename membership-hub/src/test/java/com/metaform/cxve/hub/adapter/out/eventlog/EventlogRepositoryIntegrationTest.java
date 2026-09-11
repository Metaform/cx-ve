package com.metaform.cxve.hub.adapter.out.eventlog;

import com.metaform.cxve.hub.domain.model.eventlog.EventSummary;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link EventlogRepository} against a REAL Postgres carrying the compliance
 * tracker's views (jsonb_agg/FILTER — beyond H2), created from the hand-translated schema twin
 * in src/test/resources/eventtracker-schema.sql. Skipped automatically where Docker is
 * unavailable. What is verified here is the hub's read contract: identity rollup, event-time
 * ordering, the NULL-events edge, the optional filters and the envelope round-trip.
 */
@Testcontainers(disabledWithoutDocker = true)
class EventlogRepositoryIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private static EventlogRepository repository;

    @BeforeAll
    static void setUp() throws Exception {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (var stream = EventlogRepositoryIntegrationTest.class.getResourceAsStream("/eventtracker-schema.sql");
             var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
        var jdbc = JdbcClient.create(dataSource);
        seed(jdbc);
        repository = new EventlogRepository(jdbc, new JsonMapper());
    }

    private static void seed(JdbcClient jdbc) {
        jdbc.sql("INSERT INTO participant(process_id, external_id, did, bpn, participant_context_id, state, started_at) "
                        + "VALUES ('proc-1', 'ext-1', 'did:web:one', 'BPNLONE000000001', 'pctx-1', 'RUNNING', '2026-01-01T10:00:00Z')")
                .update();
        // a second participant that has not produced any events yet
        jdbc.sql("INSERT INTO participant(process_id, state, started_at) VALUES ('proc-2', 'RUNNING', '2026-01-01T11:00:00Z')")
                .update();
        // attributed by onboarding process id
        jdbc.sql("INSERT INTO event(source, event_id, subject, type, occurred_at, recorded_at, envelope, onboarding_process_id) "
                        + "VALUES ('onboarding-api', 'ev-1', 'events.onboarding.started', 'OnboardingStarted.v1', "
                        + "'2026-01-01T10:01:00Z', '2026-01-01T10:01:01Z', '{\"a\": 1}'::jsonb, 'proc-1')")
                .update();
        // attributed by participant context id
        jdbc.sql("INSERT INTO event(source, event_id, subject, type, occurred_at, recorded_at, envelope, participant_context_id) "
                        + "VALUES ('controlplane', 'ev-2', 'events.contract.negotiation.finalized', 'Finalized.v1', "
                        + "'2026-01-01T10:30:00Z', '2026-01-01T10:30:01Z', '{\"b\": 2}'::jsonb, 'pctx-1')")
                .update();
        // no occurred_at on the wire: recorded_at is the ordering fallback (latest of the three)
        jdbc.sql("INSERT INTO event(source, event_id, subject, type, recorded_at, envelope, participant_context_id) "
                        + "VALUES ('identityhub', 'ev-3', 'events.keypair.added', 'KeyPairAdded.v1', "
                        + "'2026-01-01T10:45:00Z', '{\"c\": 3}'::jsonb, 'pctx-1')")
                .update();
        // someone else's event: must not leak into proc-1's log
        jdbc.sql("INSERT INTO event(source, event_id, subject, type, occurred_at, envelope, participant_context_id) "
                        + "VALUES ('controlplane', 'ev-4', 'events.asset.created', 'AssetCreated.v1', "
                        + "'2026-01-01T10:31:00Z', '{}'::jsonb, 'pctx-other')")
                .update();
    }

    @Test
    void rollup_unifiesIdentitiesAndOrdersEventsByEventTime() {
        var rollup = repository.findByProcessId("proc-1").orElseThrow();

        assertThat(rollup.externalId()).isEqualTo("ext-1");
        assertThat(rollup.bpn()).isEqualTo("BPNLONE000000001");
        assertThat(rollup.did()).isEqualTo("did:web:one");
        assertThat(rollup.participantContextId()).isEqualTo("pctx-1");
        assertThat(rollup.state()).isEqualTo("RUNNING");
        assertThat(rollup.eventCount()).isEqualTo(3);
        assertThat(rollup.events()).extracting(EventSummary::subject).containsExactly(
                "events.onboarding.started", "events.contract.negotiation.finalized", "events.keypair.added");
        assertThat(rollup.events()).allSatisfy(event -> {
            assertThat(event.occurredAt()).isNotNull();
            assertThat(event.eventId()).isNotBlank();
            assertThat(event.source()).isNotBlank();
        });
    }

    @Test
    void participantWithoutEvents_rollsUpToAnEmptyList() {
        var rollup = repository.findByProcessId("proc-2").orElseThrow();

        assertThat(rollup.eventCount()).isZero();
        // the view's FILTERed jsonb_agg yields SQL NULL here — the repository maps it to []
        assertThat(rollup.events()).isEmpty();
    }

    @Test
    void unknownProcessId_isEmpty() {
        assertThat(repository.findByProcessId("no-such")).isEmpty();
    }

    @Test
    void findAll_ordersDeterministicallyAndAppliesOptionalFilters() {
        assertThat(repository.findAll(null, null, null))
                .extracting(rollup -> rollup.processId())
                .containsExactly("proc-1", "proc-2");
        assertThat(repository.findAll("BPNLONE000000001", null, null))
                .singleElement()
                .satisfies(rollup -> assertThat(rollup.processId()).isEqualTo("proc-1"));
        assertThat(repository.findAll(null, "ext-1", "did:web:one"))
                .singleElement()
                .satisfies(rollup -> assertThat(rollup.processId()).isEqualTo("proc-1"));
        assertThat(repository.findAll("BPNLNOPE00000001", null, null)).isEmpty();
    }

    @Test
    void findEvents_pagesFullDetailsInEventTimeOrder() {
        var all = repository.findEvents("proc-1", 0, 10);

        assertThat(all).hasSize(3);
        assertThat(all.get(0).subject()).isEqualTo("events.onboarding.started");
        assertThat(all.get(0).envelope().path("a").asInt()).isEqualTo(1);
        assertThat(all.get(0).onboardingProcessId()).isEqualTo("proc-1");
        assertThat(all.get(2).subject()).isEqualTo("events.keypair.added");
        assertThat(all.get(2).occurredAt()).isNull();
        assertThat(all.get(2).recordedAt()).isNotNull();

        assertThat(repository.findEvents("proc-1", 1, 1))
                .singleElement()
                .satisfies(event -> assertThat(event.subject()).isEqualTo("events.contract.negotiation.finalized"));
    }
}
