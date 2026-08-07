// Package handler implements the event processor for the compliance tracker lifecycle agent.
package handler

import (
	"context"
	"strings"

	"github.com/eclipse-cfm/cfm/common/lifecycleagent"
	"github.com/eclipse-cfm/cfm/common/system"
)

// Subject prefixes the tracker dispatches on. Matching is by prefix rather than by exact subject
// so that a new leaf subject in a family (e.g. "events.issuance.rejected") reaches its handler
// instead of silently falling through to the default branch.
const (
	IssuanceSubjectPrefix = "events.issuance."
	KeyPairSubjectPrefix  = "events.keypair."
)

// Route identifies the compliance rule responsible for a subject.
type Route int

const (
	// RouteIgnore marks a subject the tracker has no rule for.
	RouteIgnore Route = iota
	// RouteIssuance marks credential issuance events.
	RouteIssuance
	// RouteKeyPair marks key lifecycle events.
	RouteKeyPair
)

// RouteFor classifies a NATS subject into the compliance rule that handles it. Kept separate from
// Process so the routing table can be exercised without a running agent.
func RouteFor(subject string) Route {
	switch {
	case strings.HasPrefix(subject, IssuanceSubjectPrefix):
		return RouteIssuance
	case strings.HasPrefix(subject, KeyPairSubjectPrefix):
		return RouteKeyPair
	default:
		return RouteIgnore
	}
}

// ComplianceEvent is the CloudEvents v1.0 envelope the tracker consumes.
type ComplianceEvent = lifecycleagent.CloudEvent[ComplianceEventData]

// Config holds the dependencies required by the Processor. Downstream clients (an HTTP client, a
// token provider, a store) get added here as the compliance rules start calling out; see
// agent/lifecycle/keymanagementagent in the CFM repo for how those are wired in the launcher.
type Config struct {
	LogMonitor system.LogMonitor
}

// Processor reacts to the lifecycle events the compliance rules are based on.
type Processor struct {
	monitor system.LogMonitor
}

// NewProcessor constructs a compliance tracker event processor.
func NewProcessor(config *Config) *Processor {
	return &Processor{
		monitor: config.LogMonitor,
	}
}

// Process handles a single lifecycle event.
//
// The return value determines the fate of the NATS message: nil acknowledges it, a recoverable
// error (types.NewRecoverableError / types.NewRecoverableWrappedError) negatively acknowledges it
// so it is redelivered, and any other error is treated as fatal — the message is acknowledged and
// dropped. Delivery is at-least-once, so handlers must be idempotent.
func (p *Processor) Process(ctx context.Context, evt lifecycleagent.EventContext[ComplianceEvent]) error {
	p.monitor.Infof("Received event on %s (type=%s, id=%s)", evt.Subject, evt.Payload.Type, evt.Payload.ID)

	switch RouteFor(evt.Subject) {
	case RouteIssuance:
		return p.handleIssuance(ctx, evt)
	case RouteKeyPair:
		return p.handleKeyPair(ctx, evt)
	default:
		// The agent may be subscribed more broadly than it has rules for; ignoring an unmodelled
		// subject acknowledges the message rather than letting it be redelivered forever.
		p.monitor.Debugf("No compliance rule for subject %s, ignoring", evt.Subject)
		return nil
	}
}

// handleIssuance processes credential issuance events (events.issuance.*) emitted by IdentityHub.
func (p *Processor) handleIssuance(_ context.Context, evt lifecycleagent.EventContext[ComplianceEvent]) error {
	data := evt.Payload.Data
	p.monitor.Debugf("Issuance event %s for participant context '%s'", evt.Subject, data.ParticipantContextID)

	// TODO: record the credential's compliance state for this participant.
	//
	// When this calls a downstream service, classify its failures: a transient one must be
	// returned as a recoverable error so the message is redelivered, e.g.
	//
	//   if err := p.someClient.Record(ctx, data.ParticipantContextID); err != nil {
	//       return types.NewRecoverableWrappedError(err, "recording issuance for '%s'", data.ParticipantContextID)
	//   }
	//
	// while a permanently malformed payload should return a plain error (or nil) so the message
	// is dropped instead of poisoning the consumer.
	return nil
}

// handleKeyPair processes key lifecycle events (events.keypair.*) emitted by IdentityHub.
func (p *Processor) handleKeyPair(_ context.Context, evt lifecycleagent.EventContext[ComplianceEvent]) error {
	data := evt.Payload.Data
	p.monitor.Debugf("Key pair event %s for participant context '%s'", evt.Subject, data.ParticipantContextID)

	// TODO: track key hygiene (rotation age, revocation) for this participant.
	//
	// Subject-specific fields that ComplianceEventData does not model — the key descriptor on
	// rotated/revoked events, for instance — are still available in evt.Raw and can be unmarshalled
	// into a purpose-built struct here.
	return nil
}
