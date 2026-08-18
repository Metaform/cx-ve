package store

import (
	"database/sql"
	"fmt"
)

const (
	eventTable   = "event"
	bindingTable = "binding"
)

// createTables creates the ledger schema. Idempotent (IF NOT EXISTS / OR REPLACE throughout), so
// it runs unconditionally at every startup — the repo has no migration tooling yet; schema
// changes that CREATE cannot express need one.
func createTables(db *sql.DB) error {
	if err := createEventTable(db); err != nil {
		return err
	}
	if err := createBindingTable(db); err != nil {
		return err
	}
	return createProcessEventView(db)
}

func createEventTable(db *sql.DB) error {
	_, err := db.Exec(fmt.Sprintf(`
		CREATE TABLE IF NOT EXISTS %[1]s (
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

func createBindingTable(db *sql.DB) error {
	_, err := db.Exec(fmt.Sprintf(`
		CREATE TABLE IF NOT EXISTS %[1]s (
			process_id             TEXT NOT NULL PRIMARY KEY,
			external_id            TEXT,
			did                    TEXT,
			bpn                    TEXT,
			participant_context_id TEXT,
			state                  TEXT NOT NULL DEFAULT 'RUNNING',
			started_at             TIMESTAMPTZ NOT NULL,
			completed_at           TIMESTAMPTZ
		);
		CREATE INDEX IF NOT EXISTS idx_binding_did ON %[1]s(did) WHERE did IS NOT NULL;
		CREATE INDEX IF NOT EXISTS idx_binding_pctx ON %[1]s(participant_context_id) WHERE participant_context_id IS NOT NULL;
		CREATE INDEX IF NOT EXISTS idx_binding_bpn ON %[1]s(bpn) WHERE bpn IS NOT NULL
	`, bindingTable))
	return err
}

// createProcessEventView creates the read-time correlation: every ledger event attributed to the
// onboarding process(es) it belongs to. Attribution rules, in order:
//
//   - An event carrying an onboarding process id (the onboarding family) attributes to exactly
//     that process, and ONLY that way — a rejected duplicate's own events carry the duplicated
//     DID and must not leak into the process they duplicated.
//   - Any other event attributes by identity (holder DID or participant context id), but only
//     within the process's attribution window: from started until, for REJECTED/FAILED, the
//     terminal event — a dead process frees its identifiers for re-registration, so its window
//     must not swallow the successor's events. A COMPLETED process owns its identity permanently
//     (the duplicate check bars re-registration), so its window never closes and the
//     participant's post-onboarding activity keeps attributing to it.
//
// Event times fall back to recorded_at (occurred_at is optional on the wire); an event matching
// no binding does not appear here — it stays queryable in the event table by its own keys.
func createProcessEventView(db *sql.DB) error {
	_, err := db.Exec(fmt.Sprintf(`
		CREATE OR REPLACE VIEW process_event AS
		SELECT b.process_id AS binding_process_id, b.state AS binding_state, e.*
		FROM %s e
		JOIN %s b ON
			CASE WHEN e.onboarding_process_id IS NOT NULL
			     THEN e.onboarding_process_id = b.process_id
			     ELSE (e.holder_did = b.did OR e.participant_context_id = b.participant_context_id)
			          AND COALESCE(e.occurred_at, e.recorded_at) >= b.started_at
			          AND (b.state IN ('RUNNING', 'COMPLETED')
			               OR COALESCE(e.occurred_at, e.recorded_at) <= b.completed_at)
			END
	`, eventTable, bindingTable))
	return err
}
