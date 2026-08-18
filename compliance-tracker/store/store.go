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

// BindingStoreKey resolves the BindingStore from the agent's service registry.
const BindingStoreKey system.ServiceType = "compliancetracker:BindingStore"

// Binding is the projection that makes the ledger correlatable: one row per onboarding process,
// built purely from the event stream (never from the Onboarding API's database — the tracker is
// an independent observer). The onboarding started event opens it with the identities known at
// submission; the DID-document published event links the participant context; the completed event
// confirms the final identities and closes it. Events are then attributed to processes at READ
// time, by joining their correlation keys against these rows — so the ledger stays immutable and
// binding knowledge learned late applies to history automatically.
type Binding struct {
	ProcessID  string
	ExternalID string
	// Did is final from the started event. Bpn is empty when the registration submitted none;
	// the assigned one arrives with the closure.
	Did string
	Bpn string
	// StartedAt opens the process's attribution window; the zero value falls back to the wall
	// clock at insert.
	StartedAt time.Time
}

// BindingClosure records a process reaching a terminal state. For REJECTED and FAILED the window
// closes — the identifiers become free for re-registration, so later events must not attribute
// here. A COMPLETED process instead owns its identity permanently: its window stays open and all
// of the participant's later activity keeps attributing to the onboarding that created it.
type BindingClosure struct {
	ProcessID string
	// The identity fields overwrite the binding when set — the completed event carries the
	// authoritative, finally-assigned values. Empty means "not carried" and leaves the binding
	// as learned. They also let a closure for a process never seen starting (the tracker may have
	// started mid-flight) record what it knows.
	ExternalID           string
	Did                  string
	Bpn                  string
	ParticipantContextID string
	// State is the terminal OnboardingState constant name: COMPLETED, REJECTED or FAILED.
	State       string
	CompletedAt time.Time
}

// BindingStore maintains the projection. Every method is idempotent — delivery is at-least-once,
// so each may run again on redelivery.
type BindingStore interface {
	// Open records a started onboarding. Re-opening an existing process is a no-op.
	Open(ctx context.Context, b *Binding) error
	// LinkParticipantContext attaches the participant context to the still-running binding of the
	// given DID (the did:web document publication is where the two first appear together).
	// Matching no binding — a context outside any onboarding, e.g. the operator's own — is fine.
	LinkParticipantContext(ctx context.Context, did, participantContextID string) error
	// Close marks the process terminal. Closing a process never opened still records what the
	// closure knows (the tracker may have started mid-flight).
	Close(ctx context.Context, c *BindingClosure) error
	// HasDid says whether any binding knows this DID. Diagnostic: an issuance holder id that
	// matches no binding means holder ids are NOT participant DIDs, and correlation would be
	// silently broken — worth a warning, not an error.
	HasDid(ctx context.Context, did string) (bool, error)
}
