// Package store persists every event the compliance tracker is delivered. The edc-events stream
// is memory storage with interest retention — once acknowledged, an event is gone — so this
// append-only ledger is the durable record the compliance rules run against.
package store

import (
	"time"

	"github.com/eclipse-cfm/cfm/common/system"
)

// EventStoreKey resolves the EventStore from the agent's service registry.
const EventStoreKey system.ServiceType = "compliancetracker:EventStore"

// ParticipantStoreKey resolves the ParticipantStore from the agent's service registry.
const ParticipantStoreKey system.ServiceType = "compliancetracker:ParticipantStore"

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

// Participant is the registry row that makes the ledger correlatable: one row per participant
// (or failed registration attempt), unifying the three identities — BPN, DID, participant
// context id — that never occur together in a single event. Built purely from the event stream
// (never from the Onboarding API's database — the tracker is an independent observer): the
// onboarding started event opens it with the identities known at submission; the DID-document
// published event links the participant context; the completed event confirms the final
// identities and closes the registration. Events are then attributed to participants at READ
// time, by joining their correlation keys against these rows — so the ledger stays immutable and
// identity knowledge learned late applies to history automatically. The onboarding process id is
// the participant's provenance and primary key: the process that created it.
type Participant struct {
	ProcessID  string
	ExternalID string
	// Did is final from the started event. Bpn is empty when the registration submitted none;
	// the assigned one arrives with the closure.
	Did string
	Bpn string
	// StartedAt is the earliest an event can be attributed to this participant; the zero value
	// falls back to the wall clock at insert.
	StartedAt time.Time
}

// ParticipantClosure records a registration reaching a terminal state. A COMPLETED participant
// owns its identity permanently: all of its later activity keeps attributing to it. For REJECTED
// and FAILED the attribution window closes with the terminal event — the identifiers become free
// for re-registration, so later events must not attribute to the dead attempt.
type ParticipantClosure struct {
	ProcessID string
	// The identity fields overwrite the participant when set — the completed event carries the
	// authoritative, finally-assigned values. Empty means "not carried" and leaves the
	// participant as learned. They also let a closure for a registration never seen starting
	// (the tracker may have started mid-flight) record what it knows.
	ExternalID           string
	Did                  string
	Bpn                  string
	ParticipantContextID string
	// State is the terminal OnboardingState constant name: COMPLETED, REJECTED or FAILED.
	State       string
	CompletedAt time.Time
}
