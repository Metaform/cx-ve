package store

import (
	"database/sql"
	"fmt"
)

const eventTable = "event"

// createTables creates the ledger schema. Idempotent (IF NOT EXISTS throughout), so it runs
// unconditionally at every startup — the repo has no migration tooling yet; schema changes that
// CREATE cannot express need one.
func createTables(db *sql.DB) error {
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
