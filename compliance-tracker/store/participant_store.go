package store

import (
	"context"
	"database/sql"
	"fmt"
)

// didDocumentPublishedSubject is the event that links a DID to its participant context — the
// handler's SubjectDidDocumentPublished (the handler depends on this package, not vice versa).
const didDocumentPublishedSubject = "events.diddocument.published"

// ParticipantStore maintains the registry. Every method is idempotent — delivery is
// at-least-once, so each may run again on redelivery.
type ParticipantStore interface {
	// Open records a started registration. Re-opening an existing participant is a no-op.
	//
	// A participant whose DID document was published BEFORE its registration started — a member
	// the platform hosts, which the Membership Hub deploys before it registers it — is linked to
	// that participant context right away (LinkParticipantContext came too early to find the
	// row), and its window starts with the context's first event, so its own provisioning (the
	// participant context, its keys, the DID document) belongs to its history. The context is the
	// one of the DID's LATEST publication: an earlier deployment under the same DID is a previous
	// life of the identity, and stays out.
	Open(ctx context.Context, p *Participant) error
	// LinkParticipantContext attaches the participant context to the LIVE (RUNNING or COMPLETED)
	// registration of the given DID (the did:web document publication is where the two first
	// appear together). COMPLETED must be linkable: a registration may complete before the
	// identity provisioning that publishes the DID document. A publication BEFORE the registration
	// starts — a hosted member, deployed first — finds no row here; Open links it then. Matching
	// no participant — a context outside any onboarding, e.g. the operator's own — is fine.
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
	if _, err := s.db.ExecContext(ctx, fmt.Sprintf(`
		INSERT INTO %s (process_id, external_id, did, bpn, started_at)
		VALUES ($1, $2, $3, $4, COALESCE($5, now()))
		ON CONFLICT (process_id) DO NOTHING
	`, participantTable),
		p.ProcessID, nullString(p.ExternalID), nullString(p.Did), nullString(p.Bpn), nullTime(p.StartedAt)); err != nil {
		return err
	}
	if p.Did == "" {
		return nil
	}
	// The DID document already published: link the context of the DID's latest publication and
	// start the window at that context's first event. Only a live row without a context yet —
	// the same guard as LinkParticipantContext, and idempotent under redelivery.
	_, err := s.db.ExecContext(ctx, fmt.Sprintf(`
		UPDATE %[1]s p SET
			participant_context_id = d.pcid,
			started_at = LEAST(p.started_at, COALESCE(
				(SELECT min(COALESCE(e.occurred_at, e.recorded_at)) FROM %[2]s e
				 WHERE e.participant_context_id = d.pcid),
				p.started_at))
		FROM (
			SELECT participant_context_id AS pcid FROM %[2]s
			WHERE subject = $3 AND holder_did = $2 AND participant_context_id IS NOT NULL
			ORDER BY COALESCE(occurred_at, recorded_at) DESC
			LIMIT 1
		) d
		WHERE p.process_id = $1 AND p.participant_context_id IS NULL AND p.state IN ('RUNNING', 'COMPLETED')
	`, participantTable, eventTable), p.ProcessID, p.Did, didDocumentPublishedSubject)
	return err
}

func (s *postgresParticipantStore) LinkParticipantContext(ctx context.Context, did, participantContextID string) error {
	// Only the LIVE registration — RUNNING or COMPLETED, the states that own their identity
	// permanently (mirroring the participant_event view): a rejected duplicate carries the DID
	// of the participant it duplicated, and the dead row must not capture the link. COMPLETED is
	// included because a registration may complete before the DID document publication — a
	// RUNNING-only guard would lose the link (and with it every pcid-correlated event) then. Zero
	// matched rows is not an error: a context outside any onboarding (e.g. the operator's own), or
	// a hosted member's, published before its registration started — Open links that one.
	_, err := s.db.ExecContext(ctx, fmt.Sprintf(`
		UPDATE %s SET participant_context_id = $2 WHERE did = $1 AND STATE IN ('RUNNING', 'COMPLETED')
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
