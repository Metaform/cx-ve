package handler

import (
	"context"
	"fmt"
	"testing"

	"github.com/eclipse-cfm/cfm/common/lifecycleagent"
	"github.com/eclipse-cfm/cfm/common/system"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// recordingMonitor captures what the processor reported, so the tests can assert that an outcome was
// not just accepted but distinguished — a rejected onboarding logged as completed would still pass a
// test that only checked the error.
type recordingMonitor struct {
	system.NoopMonitor
	infos []string
	warns []string
}

func (m *recordingMonitor) Infof(message string, args ...any) {
	m.infos = append(m.infos, fmt.Sprintf(message, args...))
}

func (m *recordingMonitor) Warnf(message string, args ...any) {
	m.warns = append(m.warns, fmt.Sprintf(message, args...))
}

func processorWith(monitor system.LogMonitor) *Processor {
	return NewProcessor(&Config{LogMonitor: monitor})
}

// event builds the envelope the framework delivers. Data is left as a map because Process
// re-marshals it before decoding into the event type, exactly as it arrives from JetStream.
func event(subject string, data map[string]any) lifecycleagent.EventContext[lifecycleagent.CloudEvent[any]] {
	return lifecycleagent.EventContext[lifecycleagent.CloudEvent[any]]{
		Subject: subject,
		Payload: lifecycleagent.CloudEvent[any]{
			SpecVersion: lifecycleagent.SpecVersion,
			ID:          "ce-1",
			Source:      "onboarding-api-7d56645cc7-2bzkl",
			Data:        data,
		},
	}
}

func TestProcess_OnboardingStarted(t *testing.T) {
	monitor := &recordingMonitor{}

	err := processorWith(monitor).Process(context.Background(), event(SubjectOnboardingStarted, map[string]any{
		"processId":  "proc-1",
		"externalId": "ext-123",
		"bpn":        "BPNL0000000001AB",
		"did":        "did:web:identity.cxve.localhost:acme",
	}))

	require.NoError(t, err)
	// Both identities must survive decoding: binding them is the whole point of tracking this event.
	require.Len(t, monitor.infos, 2) // the generic "Event received" line, then the onboarding one
	assert.Contains(t, monitor.infos[1], "proc-1")
	assert.Contains(t, monitor.infos[1], "BPNL0000000001AB")
	assert.Contains(t, monitor.infos[1], "did:web:identity.cxve.localhost:acme")
	assert.Empty(t, monitor.warns)
}

func TestProcess_OnboardingCompleted(t *testing.T) {
	monitor := &recordingMonitor{}

	err := processorWith(monitor).Process(context.Background(), event(SubjectOnboardingCompleted, map[string]any{
		"processId":            "proc-1",
		"externalId":           "ext-123",
		"bpn":                  "BPNL0000000001AB",
		"did":                  "did:web:identity.cxve.localhost:acme",
		"participantContextId": "pctx-9",
		"state":                string(OnboardingStateCompleted),
		"failureMessage":       nil,
	}))

	require.NoError(t, err)
	require.Len(t, monitor.infos, 2)
	assert.Contains(t, monitor.infos[1], "pctx-9")
	// A success is not a warning, whatever else it logs.
	assert.Empty(t, monitor.warns)
}

func TestProcess_OnboardingCompleted_ReportsANonSuccessfulOutcomeAsSuch(t *testing.T) {
	// Both legs share one subject, so a tracker that routed on the subject alone would record this
	// failed onboarding as a completed one.
	for _, outcome := range []OnboardingState{OnboardingStateFailed, OnboardingStateRejected} {
		t.Run(string(outcome), func(t *testing.T) {
			monitor := &recordingMonitor{}

			err := processorWith(monitor).Process(context.Background(), event(SubjectOnboardingCompleted, map[string]any{
				"processId":      "proc-2",
				"externalId":     "ext-2",
				"bpn":            "BPNL0000000001AB",
				"state":          string(outcome),
				"failureMessage": "participant provisioning timed out",
				// No did and no participantContextId: a failed onboarding never gets either.
			}))

			require.NoError(t, err)
			require.Len(t, monitor.warns, 1)
			assert.Contains(t, monitor.warns[0], string(outcome))
			assert.Contains(t, monitor.warns[0], "participant provisioning timed out")
			// Only the generic "Event received" line — nothing reported this as a completion.
			assert.Len(t, monitor.infos, 1)
		})
	}
}

func TestProcess_MalformedOnboardingPayload_IsDropped(t *testing.T) {
	// A payload that cannot be decoded is a fatal error by the framework's contract: the message is
	// acknowledged and dropped rather than redelivered forever. Nothing must be reported from it.
	monitor := &recordingMonitor{}

	err := processorWith(monitor).Process(context.Background(), event(SubjectOnboardingCompleted, map[string]any{
		"processId": "proc-3",
		"bpn":       42, // the wire type is a string
	}))

	require.Error(t, err)
	assert.Len(t, monitor.infos, 1)
	assert.Empty(t, monitor.warns)
}

func TestProcess_RoutesOnTheFamily_NotTheIndividualSubject(t *testing.T) {
	// Leaves that no case ever named. Dispatching on the family prefix means they are decoded into
	// the family's base struct rather than falling through, so a leaf added upstream is covered
	// without touching the switch.
	for _, subject := range []string{
		SubjectIssuanceRejected,            // never enumerated
		SubjectIssuanceErrored,             // never enumerated
		SubjectKeyPairRotated,              // never enumerated
		SubjectParticipantContextDeleted,   // never enumerated
		SubjectIssuanceCredentialDelivered, // was enumerated
	} {
		t.Run(subject, func(t *testing.T) {
			monitor := &recordingMonitor{}

			require.NoError(t, processorWith(monitor).Process(context.Background(), event(subject, map[string]any{
				"holderId":             "holder-1",
				"participantContextId": "pctx-1",
				"keyId":                "key-1",
			})))

			// A malformed payload on the same subject must now be rejected — proof the family case
			// really decodes it, rather than ignoring it as an unrecognised subject would.
			require.Error(t, processorWith(monitor).Process(context.Background(), event(subject, map[string]any{
				"participantContextId": 42, // the wire type is a string
				"holderId":             42,
				"keyId":                42,
			})))
		})
	}
}

func TestProcess_DidDocumentUnpublished_IsNotReportedAsALink(t *testing.T) {
	// The published and unpublished leaves share one payload, so the family case alone cannot tell
	// them apart — and reporting an unpublish as a link would invert its meaning.
	monitor := &recordingMonitor{}

	err := processorWith(monitor).Process(context.Background(), event(SubjectDidDocumentUnpublished, map[string]any{
		"did":                  "did:web:identity.cxve.localhost:acme",
		"participantContextId": "pctx-9",
	}))

	require.NoError(t, err)
	assert.Len(t, monitor.infos, 1) // the generic "Event received" line only
}

func TestProcess_UnhandledSubject_IsAcknowledged(t *testing.T) {
	// The agent subscribes to events.> , so it sees far more than it routes on. Anything unrecognised
	// must be acked, not treated as an error, or the stream would back up behind it.
	monitor := &recordingMonitor{}

	err := processorWith(monitor).Process(context.Background(), event(SubjectAssetCreated, map[string]any{
		"processId": "not-an-onboarding-event",
	}))

	require.NoError(t, err)
	assert.Len(t, monitor.infos, 1)
}
