// Package handler implements the event processor for the compliance tracker lifecycle agent.
package handler

import (
	"context"
	"encoding/json"
	"strings"
	"time"

	"github.com/eclipse-cfm/cfm/common/lifecycleagent"
	"github.com/eclipse-cfm/cfm/common/system"
	"github.com/eclipse-cfm/cfm/common/types"
	"github.com/metaform/cx-ve/compliance-tracker/store"
)

// Config holds the dependencies required by the Processor. Downstream clients (an HTTP client, a
// token provider) get added here as the compliance rules start calling out; see
// agent/lifecycle/keymanagementagent in the CFM repo for how those are wired in the launcher.
type Config struct {
	LogMonitor system.LogMonitor
	Events     store.EventStore
	Bindings   store.BindingStore
}

// Processor reacts to the lifecycle events the compliance rules are based on.
type Processor struct {
	monitor  system.LogMonitor
	events   store.EventStore
	bindings store.BindingStore
}

// NewProcessor constructs a compliance tracker event processor.
func NewProcessor(config *Config) *Processor {
	return &Processor{
		monitor:  config.LogMonitor,
		events:   config.Events,
		bindings: config.Bindings,
	}
}

// Process handles a single lifecycle event: extract the correlation keys the event's family
// carries, maintain the binding projection, then append the event to the ledger.
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

	// Ledger completeness beats extraction: a payload the family struct cannot decode — or a
	// family nothing here knows — is still an event that happened, so it is recorded keyless
	// rather than dropped. Only a database write may fail the message, and recoverably: the
	// database being down is no reason to ack an event into oblivion (the stream's interest
	// retention drops it on ack), it is a reason to have it redelivered — track wraps its
	// projection writes accordingly, and every projection statement is idempotent, so the rerun
	// after redelivery is safe.
	keys, err := p.track(ctx, evt.Subject, evt.Payload.Time, jsonRaw)
	if err != nil {
		if types.IsRecoverable(err) {
			return err
		}
		p.monitor.Warnf("Event on %s does not decode (%s); recording it without correlation keys", evt.Subject, err)
		keys = store.CorrelationKeys{}
	}

	err = p.events.Record(ctx, &store.EventRecord{
		Source:     evt.Payload.Source,
		EventID:    evt.Payload.ID,
		Subject:    evt.Subject,
		Type:       evt.Payload.Type,
		OccurredAt: evt.Payload.Time,
		// The raw message body, not a re-marshal of the typed envelope: the typed form drops
		// members it does not declare, and the ledger must not inherit that loss.
		Envelope: evt.Raw,
		Keys:     keys,
	})
	if err != nil {
		return types.NewRecoverableWrappedError(err, "failed to record event %s on %s", evt.Payload.ID, evt.Subject)
	}
	return nil
}

// track decodes the correlation keys an event's family carries, maintains the binding projection
// on the events that advance it (onboarding start/completion, the DID-document publication), and
// reports the occurrences the tracker narrates.
//
// Dispatched on the event FAMILY rather than the individual occurrence, decoding the family's
// base struct — the fields every leaf in the family shares. A leaf added upstream (a new issuance
// or key-pair event) then arrives already decoded and handled instead of falling through
// unrecognized. Where a rule needs to know which occurrence it was, the subject says.
//
// A returned decode error means "record keyless"; projection failures come back wrapped
// recoverable, meaning "redeliver".
func (p *Processor) track(ctx context.Context, subject string, occurredAt time.Time, jsonRaw []byte) (store.CorrelationKeys, error) {
	switch {
	case strings.HasPrefix(subject, SubjectPrefixAsset):
		edcEvt := AssetEvent{}
		err := json.Unmarshal(jsonRaw, &edcEvt)
		return store.CorrelationKeys{ParticipantContextID: edcEvt.ParticipantContextID}, err
	case strings.HasPrefix(subject, SubjectPrefixContractDefinition):
		edcEvt := ContractDefinitionEvent{}
		err := json.Unmarshal(jsonRaw, &edcEvt)
		return store.CorrelationKeys{ParticipantContextID: edcEvt.ParticipantContextID}, err
	case strings.HasPrefix(subject, SubjectPrefixContractNegotiation):
		edcEvt := ContractNegotiationEvent{}
		err := json.Unmarshal(jsonRaw, &edcEvt)
		return store.CorrelationKeys{ParticipantContextID: edcEvt.ParticipantContextID}, err
	case strings.HasPrefix(subject, SubjectPrefixPolicyDefinition):
		edcEvt := PolicyDefinitionEvent{}
		err := json.Unmarshal(jsonRaw, &edcEvt)
		return store.CorrelationKeys{ParticipantContextID: edcEvt.ParticipantContextID}, err
	case strings.HasPrefix(subject, SubjectPrefixTransferProcess):
		edcEvt := TransferProcessEvent{}
		err := json.Unmarshal(jsonRaw, &edcEvt)
		return store.CorrelationKeys{ParticipantContextID: edcEvt.ParticipantContextID}, err
	case strings.HasPrefix(subject, SubjectPrefixKeyPair):
		edcEvt := KeyPairEvent{}
		err := json.Unmarshal(jsonRaw, &edcEvt)
		return store.CorrelationKeys{ParticipantContextID: edcEvt.ParticipantContextID}, err
	case strings.HasPrefix(subject, SubjectPrefixDidDocument):
		edcEvt := DidDocumentEvent{}
		err := json.Unmarshal(jsonRaw, &edcEvt)
		// The published and unpublished leaves carry an identical payload, so the subject is the only
		// thing that says whether the binding is being established or torn down.
		if err == nil && subject == SubjectDidDocumentPublished {
			// The publication is where the DID and the participant context first appear together —
			// the one event that can teach the binding which context belongs to which onboarding.
			p.monitor.Infof("Linking <%s> and <%s>", edcEvt.ParticipantContextID, edcEvt.Did)
			if lerr := p.bindings.LinkParticipantContext(ctx, edcEvt.Did, edcEvt.ParticipantContextID); lerr != nil {
				return store.CorrelationKeys{}, types.NewRecoverableWrappedError(lerr,
					"failed to link participant context %s to %s", edcEvt.ParticipantContextID, edcEvt.Did)
			}
		}
		return store.CorrelationKeys{ParticipantContextID: edcEvt.ParticipantContextID, HolderDid: edcEvt.Did}, err
	case strings.HasPrefix(subject, SubjectPrefixParticipantContext):
		edcEvt := ParticipantContextEvent{}
		err := json.Unmarshal(jsonRaw, &edcEvt)
		return store.CorrelationKeys{ParticipantContextID: edcEvt.ParticipantContextID}, err
	case strings.HasPrefix(subject, SubjectPrefixCredentialOffer):
		edcEvt := CredentialOfferEvent{}
		err := json.Unmarshal(jsonRaw, &edcEvt)
		return store.CorrelationKeys{ParticipantContextID: edcEvt.ParticipantContextID}, err
	case strings.HasPrefix(subject, SubjectPrefixIssuance):
		edcEvt := IssuanceEvent{}
		err := json.Unmarshal(jsonRaw, &edcEvt)
		// HolderID only: IssuerParticipantContextID is the ISSUER's context, and putting it in
		// the participant-context column would attribute the participant's issuance to the operator.
		if err == nil && edcEvt.HolderID != "" {
			// Correlation rests on the ASSUMPTION that issuance holder ids are participant DIDs.
			// A holder id no binding knows means either the assumption is wrong (correlation is
			// silently broken and must move to another id) or an issuance outside any onboarding —
			// both worth surfacing, neither an error.
			if known, herr := p.bindings.HasDid(ctx, edcEvt.HolderID); herr != nil {
				return store.CorrelationKeys{}, types.NewRecoverableWrappedError(herr,
					"failed to check the holder binding for %s", edcEvt.HolderID)
			} else if !known {
				p.monitor.Warnf("Issuance event on %s: holder <%s> matches no onboarding binding — "+
					"if this is an onboarding's issuance, holder ids are not participant DIDs and correlation is broken",
					subject, edcEvt.HolderID)
			}
		}
		return store.CorrelationKeys{HolderDid: edcEvt.HolderID}, err
	case strings.HasPrefix(subject, SubjectPrefixOnboarding):
		return p.onboarding(ctx, subject, occurredAt, jsonRaw)
	}
	// A family nothing routes on (secrets, or something new upstream) carries no keys the ledger
	// knows how to promote — recorded all the same.
	return store.CorrelationKeys{}, nil
}

// onboarding extracts and reports a cx-ve onboarding lifecycle event, and maintains the binding
// projection: started opens the process's binding, completed closes it.
//
// The only family whose leaves do not share a payload: the outcome fields exist on the completed
// event alone, so decoding OnboardingEvent would drop exactly what makes the event worth tracking.
// Hence the occurrence, not just the family.
func (p *Processor) onboarding(ctx context.Context, subject string, occurredAt time.Time, jsonRaw []byte) (store.CorrelationKeys, error) {
	switch subject {
	case SubjectOnboardingStarted:
		event := OnboardingStartedEvent{}
		if err := json.Unmarshal(jsonRaw, &event); err != nil {
			return store.CorrelationKeys{}, err
		}
		// The one event carrying the BPN and the DID together before anything is provisioned, so it
		// is what lets the participant-context and issuance events that follow — which carry only a
		// context id or a holder id — be attributed to a partner.
		p.monitor.Infof("Onboarding %s started: BPN <%s>, DID <%s>", event.ProcessID, event.Bpn, event.Did)
		if err := p.bindings.Open(ctx, &store.Binding{
			ProcessID:  event.ProcessID,
			ExternalID: event.ExternalID,
			Did:        event.Did,
			Bpn:        event.Bpn,
			StartedAt:  occurredAt,
		}); err != nil {
			return store.CorrelationKeys{}, types.NewRecoverableWrappedError(err,
				"failed to open the binding for onboarding %s", event.ProcessID)
		}
		return store.CorrelationKeys{OnboardingProcessID: event.ProcessID, Bpn: event.Bpn, HolderDid: event.Did}, nil
	case SubjectOnboardingCompleted:
		event := OnboardingCompletedEvent{}
		if err := json.Unmarshal(jsonRaw, &event); err != nil {
			return store.CorrelationKeys{}, err
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
		if err := p.bindings.Close(ctx, &store.BindingClosure{
			ProcessID:            event.ProcessID,
			ExternalID:           event.ExternalID,
			Did:                  event.Did,
			Bpn:                  event.Bpn,
			ParticipantContextID: event.ParticipantContextID,
			State:                string(event.State),
			CompletedAt:          occurredAt,
		}); err != nil {
			return store.CorrelationKeys{}, types.NewRecoverableWrappedError(err,
				"failed to close the binding for onboarding %s", event.ProcessID)
		}
		return store.CorrelationKeys{
			OnboardingProcessID:  event.ProcessID,
			Bpn:                  event.Bpn,
			HolderDid:            event.Did,
			ParticipantContextID: event.ParticipantContextID,
		}, nil
	}
	// A new onboarding leaf still shares the family base — enough for the keys, if not a report.
	event := OnboardingEvent{}
	err := json.Unmarshal(jsonRaw, &event)
	return store.CorrelationKeys{OnboardingProcessID: event.ProcessID, Bpn: event.Bpn, HolderDid: event.Did}, err
}
