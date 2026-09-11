-- Hand-translated twin of the compliance tracker's schema, ONLY for the hub's eventlog
-- repository test (EventlogRepositoryIntegrationTest). The tracker's
-- compliance-tracker/store/tables.go is the AUTHORITY — it creates this schema at the tracker's
-- startup and carries a pointer back to this file; a change there must be mirrored here (the
-- production hub reads the deployed views, so drift breaks this test, not the runtime).
-- Indexes are deliberately omitted: they carry no semantics the views depend on.

CREATE TABLE IF NOT EXISTS event (
    source                 TEXT NOT NULL,
    event_id               TEXT NOT NULL,
    subject                TEXT NOT NULL,
    type                   TEXT NOT NULL,
    occurred_at            TIMESTAMPTZ,
    recorded_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    envelope               JSONB NOT NULL,
    participant_context_id TEXT,
    holder_did             TEXT,
    bpn                    TEXT,
    onboarding_process_id  TEXT,
    PRIMARY KEY (source, event_id)
);

CREATE TABLE IF NOT EXISTS participant (
    process_id             TEXT NOT NULL PRIMARY KEY,
    external_id            TEXT,
    did                    TEXT,
    bpn                    TEXT,
    participant_context_id TEXT,
    state                  TEXT NOT NULL DEFAULT 'RUNNING',
    started_at             TIMESTAMPTZ NOT NULL,
    completed_at           TIMESTAMPTZ
);

CREATE OR REPLACE VIEW participant_event AS
SELECT p.process_id AS participant_id, p.state AS participant_state, e.*
FROM event e
JOIN participant p ON
    CASE WHEN e.onboarding_process_id IS NOT NULL
         THEN e.onboarding_process_id = p.process_id
         ELSE (e.holder_did = p.did OR e.participant_context_id = p.participant_context_id)
              AND COALESCE(e.occurred_at, e.recorded_at) >= p.started_at
              AND (p.state IN ('RUNNING', 'COMPLETED')
                   OR COALESCE(e.occurred_at, e.recorded_at) <= p.completed_at)
    END;

CREATE OR REPLACE VIEW participant_eventlog AS
SELECT
    p.process_id,
    p.external_id,
    p.bpn,
    p.did,
    p.participant_context_id,
    p.state,
    count(e.event_id) AS event_count,
    jsonb_agg(
        jsonb_build_object(
            'occurred_at', COALESCE(e.occurred_at, e.recorded_at),
            'subject', e.subject,
            'type', e.type,
            'source', e.source,
            'event_id', e.event_id
        ) ORDER BY COALESCE(e.occurred_at, e.recorded_at)
    ) FILTER (WHERE e.event_id IS NOT NULL) AS events
FROM participant p
LEFT JOIN participant_event e ON e.participant_id = p.process_id
GROUP BY p.process_id, p.external_id, p.bpn, p.did, p.participant_context_id, p.state;
