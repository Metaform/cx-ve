// Package store persists every event the compliance tracker is delivered. The edc-events stream
// is memory storage with interest retention — once acknowledged, an event is gone — so this
// append-only ledger is the durable record the compliance rules run against.
package store

import (
	"context"
	"time"

	"github.com/eclipse-cfm/cfm/common/system"
)

// EventStoreKey resolves the EventStore from the agent's service registry.
const EventStoreKey system.ServiceType = "compliancetracker:EventStore"

// CorrelationKeys are the identifiers promoted out of an event's payload into indexed columns, so
// events can be attributed to a participant without unpacking JSON. Which key a family carries
// differs (issuance events know a holder DID, control-plane events a participant context id);
// whatever is absent stays empty and is stored as NULL.
type CorrelationKeys struct {
	ParticipantContextID string
	HolderDid            string
	Bpn                  string
	OnboardingProcessID  string
}

// EventRecord is one received event, ready to be written to the ledger.
type EventRecord struct {
	// Source and EventID together identify the event: the CloudEvents spec scopes id uniqueness
	// to the producer, and redelivery (delivery is at-least-once) repeats both unchanged — which
	// is what makes recording idempotent.
	Source  string
	EventID string
	Subject string
	Type    string
	// OccurredAt is the CloudEvent time; the zero value (producer sent none) is stored as NULL.
	OccurredAt time.Time
	// Envelope is the raw structured-mode CloudEvent JSON exactly as delivered. Raw, because the
	// typed envelope drops members it does not declare (the onboarding events' sourcebpn and
	// participantdid extensions, for instance) — the ledger must not inherit that loss.
	Envelope []byte
	Keys     CorrelationKeys
}

// EventStore is the ledger. Record is idempotent: writing the same (Source, EventID) again is a
// no-op, not an error.
type EventStore interface {
	Record(ctx context.Context, e *EventRecord) error
}
