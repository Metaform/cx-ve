// Package handler implements the event processor for the compliance tracker lifecycle agent.
package handler

import (
	"context"
	"encoding/json"
	"strings"

	"github.com/eclipse-cfm/cfm/common/lifecycleagent"
	"github.com/eclipse-cfm/cfm/common/system"
)

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
func (p *Processor) Process(ctx context.Context, evt lifecycleagent.EventContext[lifecycleagent.CloudEvent[any]]) error {

	p.monitor.Infof("Event received: [%s] sends [%s]", evt.Payload.Source, evt.Subject)

	jsonRaw, err := json.Marshal(evt.Payload.Data)
	if err != nil {
		return err
	}

	// Dispatched on the event FAMILY rather than the individual occurrence, decoding the family's
	// base struct — the fields every leaf in the family shares. A leaf added upstream (a new
	// issuance or key-pair event) then arrives already decoded and handled instead of falling
	// through unrecognised. Where a rule needs to know which occurrence it was, the subject says.
	subject := evt.Subject
	switch {
	case strings.HasPrefix(subject, SubjectPrefixKeyPair):
		edcEvt := KeyPairEvent{}
		err = json.Unmarshal(jsonRaw, &edcEvt)
		_ = edcEvt
	case strings.HasPrefix(subject, SubjectPrefixDidDocument):
		edcEvt := DidDocumentEvent{}
		err = json.Unmarshal(jsonRaw, &edcEvt)
		// The published and unpublished leaves carry an identical payload, so the subject is the only
		// thing that says whether the binding is being established or torn down.
		if err == nil && subject == SubjectDidDocumentPublished {
			// linking those two
			p.monitor.Infof("Linking <%s> and <%s>", edcEvt.ParticipantContextID, edcEvt.Did)
		}
	case strings.HasPrefix(subject, SubjectPrefixParticipantContext):
		edcEvt := ParticipantContextEvent{}
		err = json.Unmarshal(jsonRaw, &edcEvt)
		_ = edcEvt
	case strings.HasPrefix(subject, SubjectPrefixIssuance):
		edcEvt := IssuanceEvent{}
		err = json.Unmarshal(jsonRaw, &edcEvt)
		_ = edcEvt
	case strings.HasPrefix(subject, SubjectPrefixOnboarding):
		err = p.onboarding(subject, jsonRaw)
	}
	if err != nil {
		return err
	}
	return nil
}

// onboarding reports a cx-ve onboarding lifecycle event.
//
// The only family whose leaves do not share a payload: the outcome fields exist on the completed
// event alone, so decoding OnboardingEvent would drop exactly what makes the event worth tracking.
// Hence the occurrence, not just the family.
func (p *Processor) onboarding(subject string, jsonRaw []byte) error {
	switch subject {
	case SubjectOnboardingStarted:
		event := OnboardingStartedEvent{}
		if err := json.Unmarshal(jsonRaw, &event); err != nil {
			return err
		}
		// The one event carrying the BPN and the DID together before anything is provisioned, so it
		// is what lets the participant-context and issuance events that follow — which carry only a
		// context id or a holder id — be attributed to a partner.
		p.monitor.Infof("Onboarding %s started: BPN <%s>, DID <%s>", event.ProcessID, event.Bpn, event.Did)
	case SubjectOnboardingCompleted:
		event := OnboardingCompletedEvent{}
		if err := json.Unmarshal(jsonRaw, &event); err != nil {
			return err
		}
		// Announced for every terminal outcome, so the state decides how it is reported: taking the
		// subject alone would read a rejection or a failure as a successful onboarding.
		if event.State == OnboardingStateCompleted {
			p.monitor.Infof("Onboarding %s completed: BPN <%s>, DID <%s>, participant context <%s>",
				event.ProcessID, event.Bpn, event.Did, event.ParticipantContextID)
		} else {
			p.monitor.Warnf("Onboarding %s ended as %s (BPN <%s>): %s",
				event.ProcessID, event.State, event.Bpn, event.FailureMessage)
		}
	}
	return nil
}
