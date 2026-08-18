package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"fmt"
	"time"
)

// postgresEventStore writes the ledger with plain SQL. Deliberately not the generic
// sqlstore.EntityStore: that models versioned mutable entities in a single JSONB column, while
// this table is append-only with promoted key columns and conflict-ignoring inserts.
type postgresEventStore struct {
	db *sql.DB
}

func newPostgresEventStore(db *sql.DB) *postgresEventStore {
	return &postgresEventStore{db: db}
}

// Record appends the event. ON CONFLICT DO NOTHING makes redelivery (at-least-once) a no-op, so
// the caller can always ack after a nil return. Errors are plain — mapping them onto the
// framework's ack semantics (recoverable → NAK) is the handler's job.
func (s *postgresEventStore) Record(ctx context.Context, e *EventRecord) error {
	if len(e.Envelope) == 0 {
		return fmt.Errorf("event %s on %s has no envelope", e.EventID, e.Subject)
	}
	_, err := s.db.ExecContext(ctx, fmt.Sprintf(`
		INSERT INTO %s (source, event_id, subject, type, occurred_at, envelope,
		                participant_context_id, holder_did, bpn, onboarding_process_id)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
		ON CONFLICT (source, event_id) DO NOTHING
	`, eventTable),
		e.Source, eventID(e), e.Subject, e.Type, nullTime(e.OccurredAt), string(e.Envelope),
		nullString(e.Keys.ParticipantContextID), nullString(e.Keys.HolderDid),
		nullString(e.Keys.Bpn), nullString(e.Keys.OnboardingProcessID))
	return err
}

// eventID falls back to a digest of the envelope when the producer sent no id, so a malformed
// event still dedupes deterministically instead of colliding with every other id-less one.
func eventID(e *EventRecord) string {
	if e.EventID != "" {
		return e.EventID
	}
	digest := sha256.Sum256(e.Envelope)
	return "sha256:" + hex.EncodeToString(digest[:])
}

func nullString(s string) sql.NullString {
	return sql.NullString{String: s, Valid: s != ""}
}

func nullTime(t time.Time) sql.NullTime {
	return sql.NullTime{Time: t, Valid: !t.IsZero()}
}
