package handler

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/eclipse-cfm/cfm/common/lifecycleagent"
	"github.com/eclipse-cfm/cfm/common/system"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestRouteFor(t *testing.T) {
	tests := []struct {
		subject string
		want    Route
	}{
		{"events.issuance.approved", RouteIssuance},
		{"events.issuance.delivered", RouteIssuance},
		// Prefix matching, so a leaf subject that does not exist yet still routes to the
		// issuance rule rather than being silently ignored.
		{"events.issuance.some.future.event", RouteIssuance},
		{"events.keypair.activated", RouteKeyPair},
		{"events.keypair.revoked", RouteKeyPair},
		{"events.keypair.rotated", RouteKeyPair},
		{"events.asset.created", RouteIgnore},
		{"events.issuance", RouteIgnore}, // no trailing dot: not a member of the family
		{"", RouteIgnore},
	}

	for _, test := range tests {
		t.Run(test.subject, func(t *testing.T) {
			assert.Equal(t, test.want, RouteFor(test.subject))
		})
	}
}

func TestProcess(t *testing.T) {
	subjects := []string{
		"events.issuance.delivered",
		"events.keypair.activated",
		"events.asset.created", // unmodelled: must be acknowledged, not redelivered
	}

	processor := NewProcessor(&Config{LogMonitor: system.NoopMonitor{}})

	for _, subject := range subjects {
		t.Run(subject, func(t *testing.T) {
			evt := lifecycleagent.EventContext[ComplianceEvent]{
				Subject: subject,
				Payload: ComplianceEvent{
					SpecVersion: lifecycleagent.SpecVersion,
					ID:          "ce-1",
					Source:      "/cxve/test",
					Type:        "io.cfm.test",
					Data:        ComplianceEventData{ParticipantContextID: "participant-1"},
				},
			}

			// nil acknowledges the message; a recoverable error would have it redelivered.
			assert.NoError(t, processor.Process(context.Background(), evt))
		})
	}
}

// The framework unmarshals the raw NATS message straight into CloudEvent[ComplianceEventData], so
// the struct tags must line up with the wire format that CFM agents emit.
func TestComplianceEventUnmarshalling(t *testing.T) {
	raw := []byte(`{
		"specversion": "1.0",
		"id": "ce-1",
		"source": "/cfm/identityhub",
		"type": "io.cfm.keypair.activated",
		"time": "2026-08-07T10:00:00Z",
		"datacontenttype": "application/json",
		"data": {
			"participantContextId": "participant-1",
			"id": "key-1",
			"unmodelledField": "ignored"
		}
	}`)

	var evt ComplianceEvent
	require.NoError(t, json.Unmarshal(raw, &evt))

	assert.Equal(t, "ce-1", evt.ID)
	assert.Equal(t, "io.cfm.keypair.activated", evt.Type)
	assert.Equal(t, "participant-1", evt.Data.ParticipantContextID)
	assert.Equal(t, "key-1", evt.Data.ID)
	// Unmodelled fields must not fail the decode — an unmarshalling error makes the framework
	// drop the message (common/natsclient/processor.go acks undecodable payloads).
	assert.Empty(t, evt.Data.ParticipantID)
}
