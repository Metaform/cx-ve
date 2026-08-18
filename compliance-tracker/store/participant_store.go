package store

import (
	"context"
	"database/sql"
	"fmt"
)

// ParticipantStore maintains the registry. Every method is idempotent — delivery is
// at-least-once, so each may run again on redelivery.
type ParticipantStore interface {
	// Open records a started registration. Re-opening an existing participant is a no-op.
	Open(ctx context.Context, p *Participant) error
	// LinkParticipantContext attaches the participant context to the still-running registration
	// of the given DID (the did:web document publication is where the two first appear together).
	// Matching no participant — a context outside any onboarding, e.g. the operator's own — is
	// fine.
	LinkParticipantContext(ctx context.Context, did, participantContextID string) error
	// Close marks the registration terminal. Closing one never opened still records what the
	// closure knows (the tracker may have started mid-flight).
	Close(ctx context.Context, c *ParticipantClosure) error
	// HasDid says whether any participant carries this DID. Diagnostic: an issuance holder id
	// that matches no participant means holder ids are NOT participant DIDs, and correlation
	// would be silently broken — worth a warning, not an error.
	HasDid(ctx context.Context, did string) (bool, error)
}

// postgresParticipantStore maintains the participant registry with plain SQL. Every statement is
// idempotent under redelivery: Open ignores an existing row, Link and Close overwrite with the
// same values they wrote before.
type postgresParticipantStore struct {
	db *sql.DB
}

func newPostgresParticipantStore(db *sql.DB) *postgresParticipantStore {
	return &postgresParticipantStore{db: db}
}

func (s *postgresParticipantStore) Open(ctx context.Context, p *Participant) error {
	// DO NOTHING rather than upsert: a redelivered started event must not re-open a registration
	// its completed event already closed.
	_, err := s.db.ExecContext(ctx, fmt.Sprintf(`
		INSERT INTO %s (process_id, external_id, did, bpn, started_at)
		VALUES ($1, $2, $3, $4, COALESCE($5, now()))
		ON CONFLICT (process_id) DO NOTHING
	`, participantTable),
		p.ProcessID, nullString(p.ExternalID), nullString(p.Did), nullString(p.Bpn), nullTime(p.StartedAt))
	return err
}

func (s *postgresParticipantStore) LinkParticipantContext(ctx context.Context, did, participantContextID string) error {
	// Only the RUNNING registration: a rejected duplicate carries the DID of the participant it
	// duplicated, and the dead row must not capture the link. Zero matched rows (a context
	// outside any onboarding, e.g. the operator's own) is not an error.
	_, err := s.db.ExecContext(ctx, fmt.Sprintf(`
		UPDATE %s SET participant_context_id = $2 WHERE did = $1 AND STATE = 'RUNNING'
	`, participantTable), did, participantContextID)
	return err
}

func (s *postgresParticipantStore) Close(ctx context.Context, c *ParticipantClosure) error {
	// An upsert, so a closure for a never-opened registration still lands: started_at then equals
	// completed_at — a zero-width window, which is honest, since nothing observed during the
	// registration's lifetime made it into the ledger either. The identity fields overwrite only
	// when the closure carries them.
	_, err := s.db.ExecContext(ctx, fmt.Sprintf(`
		INSERT INTO %[1]s (process_id, external_id, did, bpn, participant_context_id, STATE, started_at, completed_at)
		VALUES ($1, $2, $3, $4, $5, $6, COALESCE($7, now()), COALESCE($7, now()))
		ON CONFLICT (process_id) DO UPDATE SET
			external_id            = COALESCE(EXCLUDED.external_id, %[1]s.external_id),
			did                    = COALESCE(EXCLUDED.did, %[1]s.did),
			bpn                    = COALESCE(EXCLUDED.bpn, %[1]s.bpn),
			participant_context_id = COALESCE(EXCLUDED.participant_context_id, %[1]s.participant_context_id),
			STATE                  = EXCLUDED.state,
			completed_at           = EXCLUDED.completed_at
	`, participantTable),
		c.ProcessID, nullString(c.ExternalID), nullString(c.Did), nullString(c.Bpn),
		nullString(c.ParticipantContextID), c.State, nullTime(c.CompletedAt))
	return err
}

func (s *postgresParticipantStore) HasDid(ctx context.Context, did string) (bool, error) {
	var known bool
	err := s.db.QueryRowContext(ctx,
		fmt.Sprintf(`SELECT EXISTS(SELECT 1 FROM %s WHERE did = $1)`, participantTable), did).Scan(&known)
	return known, err
}
