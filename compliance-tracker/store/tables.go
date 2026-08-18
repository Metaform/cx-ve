package store

import (
	"database/sql"
	"fmt"
)

const (
	eventTable       = "event"
	participantTable = "participant"
)

// createTables creates the ledger schema. Idempotent (IF NOT EXISTS / OR REPLACE throughout), so
// it runs unconditionally at every startup — the repo has no migration tooling yet; schema
// changes that CREATE cannot express need one.
func createTables(db *sql.DB) error {
	if err := createEventTable(db); err != nil {
		return err
	}
	if err := createParticipantTable(db); err != nil {
		return err
	}
	return createParticipantEventView(db)
}

// createEventTable creates the hard-fact ledger of all events that occurred, just as they occurred.
func createEventTable(db *sql.DB) error {
	_, err := db.Exec(fmt.Sprintf(`
		CREATE TABLE IF NOT EXISTS %[1]s (
			SOURCE                 TEXT NOT NULL,
			event_id               TEXT NOT NULL,
			subject                TEXT NOT NULL,
			TYPE                   TEXT NOT NULL,
			occurred_at            TIMESTAMPTZ,
			recorded_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
			envelope               JSONB NOT NULL,
			participant_context_id TEXT,
			holder_did             TEXT,
			bpn                    TEXT,
			onboarding_process_id  TEXT,
			PRIMARY KEY (SOURCE, event_id)
		);
		CREATE INDEX IF NOT EXISTS idx_event_subject ON %[1]s(subject);
		CREATE INDEX IF NOT EXISTS idx_event_occurred_at ON %[1]s(occurred_at);
		-- Partial: most events carry only one of the four keys, so full indexes would be mostly NULLs
		CREATE INDEX IF NOT EXISTS idx_event_pctx ON %[1]s(participant_context_id) WHERE participant_context_id IS NOT NULL;
		CREATE INDEX IF NOT EXISTS idx_event_holder_did ON %[1]s(holder_did) WHERE holder_did IS NOT NULL;
		CREATE INDEX IF NOT EXISTS idx_event_bpn ON %[1]s(bpn) WHERE bpn IS NOT NULL;
		CREATE INDEX IF NOT EXISTS idx_event_process ON %[1]s(onboarding_process_id) WHERE onboarding_process_id IS NOT NULL
	`, eventTable))
	return err
}

// createParticipantTable creates the participant registry: one row per participant (or failed
// registration attempt), which is what lets events be attributed to a _participant_ rather than
// an _identity_. Identities are comprised of BPN, DID and participant context ID, and those three
// never occur in the same event — this row is where they are unified, learned from the onboarding
// lifecycle events. The onboarding process id is the primary key as the participant's provenance:
// the process that created it.
func createParticipantTable(db *sql.DB) error {
	_, err := db.Exec(fmt.Sprintf(`
		CREATE TABLE IF NOT EXISTS %[1]s (
			process_id             TEXT NOT NULL PRIMARY KEY,
			external_id            TEXT,
			did                    TEXT,
			bpn                    TEXT,
			participant_context_id TEXT,
			STATE                  TEXT NOT NULL DEFAULT 'RUNNING',
			started_at             TIMESTAMPTZ NOT NULL,
			completed_at           TIMESTAMPTZ
		);
		CREATE INDEX IF NOT EXISTS idx_participant_did ON %[1]s(did) WHERE did IS NOT NULL;
		CREATE INDEX IF NOT EXISTS idx_participant_pctx ON %[1]s(participant_context_id) WHERE participant_context_id IS NOT NULL;
		CREATE INDEX IF NOT EXISTS idx_participant_bpn ON %[1]s(bpn) WHERE bpn IS NOT NULL
	`, participantTable))
	return err
}

// createParticipantEventView creates the read-time correlation: every ledger event attributed to
// the participant(s) it concerns. Attribution rules, in order:
//
//   - An event carrying an onboarding process id (the onboarding family) attributes to exactly
//     the participant that process created, and ONLY that way — a rejected duplicate's own
//     events carry the duplicated DID and must not leak into the participant they duplicated.
//   - Any other event attributes by identity (holder DID or participant context id). A live
//     participant (RUNNING or COMPLETED) owns its identity permanently — the duplicate check
//     bars re-registration — so ALL of its activity attributes to it, however long after
//     onboarding. The time window is the exception, fencing off REJECTED/FAILED registration
//     attempts: a dead attempt frees its identifiers for re-registration, so only events between
//     its start and its terminal event may attribute to it.
//
// Event times fall back to recorded_at (occurred_at is optional on the wire); an event matching
// no participant does not appear here — it stays queryable in the event table by its own keys.
func createParticipantEventView(db *sql.DB) error {
	_, err := db.Exec(fmt.Sprintf(`
		CREATE OR REPLACE VIEW participant_event AS
		SELECT p.process_id AS participant_id, p.state AS participant_state, e.*
		FROM %s e
		JOIN %s p ON
			CASE WHEN e.onboarding_process_id IS NOT NULL
			     THEN e.onboarding_process_id = p.process_id
			     ELSE (e.holder_did = p.did OR e.participant_context_id = p.participant_context_id)
			          AND COALESCE(e.occurred_at, e.recorded_at) >= p.started_at
			          AND (p.state IN ('RUNNING', 'COMPLETED')
			               OR COALESCE(e.occurred_at, e.recorded_at) <= p.completed_at)
			END
	`, eventTable, participantTable))
	return err
}
