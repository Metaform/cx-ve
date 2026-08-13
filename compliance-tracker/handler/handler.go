// Package handler implements the event processor for the compliance tracker lifecycle agent.
package handler

import (
	"context"
	"encoding/json"

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

	subject := evt.Subject
	switch subject {
	case "events.keypair.added":
		edcEvt := KeyPairAddedEvent{}
		err = json.Unmarshal(jsonRaw, &edcEvt)
		_ = edcEvt
	case "events.keypair.activated":
		edcEvt := KeyPairActivatedEvent{}
		err = json.Unmarshal(jsonRaw, &edcEvt)
		_ = edcEvt
	case "events.diddocument.published":
		edcEvt := DidDocumentPublishedEvent{}
		err = json.Unmarshal(jsonRaw, &edcEvt)
		did := edcEvt.Did
		pcId := edcEvt.ParticipantContextID
		// linking those two
		p.monitor.Infof("Linking <%s> and <%s>", pcId, did)
	case "events.participantcontext.updated":
		edcEvt := ParticipantContextUpdatedEvent{}
		err = json.Unmarshal(jsonRaw, &edcEvt)
		_ = edcEvt
	case "events.participantcontext.created":
		edcEvt := ParticipantContextCreatedEvent{}
		err = json.Unmarshal(jsonRaw, &edcEvt)
		_ = edcEvt
	case "events.issuance.received":
		edcEvt := IssuanceReceivedEvent{}
		err = json.Unmarshal(jsonRaw, &edcEvt)
		_ = edcEvt
	case "events.issuance.requested":
		edcEvt := IssuanceRequestedEvent{}
		err = json.Unmarshal(jsonRaw, &edcEvt)
		_ = edcEvt
	case "events.issuance.approved":
		edcEvt := IssuanceApprovedEvent{}
		err = json.Unmarshal(jsonRaw, &edcEvt)
		_ = edcEvt
	case "events.issuance.credential.generated":
		edcEvt := CredentialGeneratedEvent{}
		err = json.Unmarshal(jsonRaw, &edcEvt)
		_ = edcEvt
	case "events.issuance.credential.delivered":
		edcEvt := CredentialDeliveredEvent{}
		err = json.Unmarshal(jsonRaw, &edcEvt)
		_ = edcEvt
	}
	if err != nil {
		return err
	}
	return nil
}
