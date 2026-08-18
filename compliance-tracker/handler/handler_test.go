package handler

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"testing"

	"github.com/eclipse-cfm/cfm/common/lifecycleagent"
	"github.com/eclipse-cfm/cfm/common/system"
	"github.com/eclipse-cfm/cfm/common/types"
	"github.com/metaform/cx-ve/compliance-tracker/store"
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

// recordingStore captures what the processor persisted — the ledger is the tracker's actual
// output, so most tests assert against it rather than the log.
type recordingStore struct {
	records []*store.EventRecord
	err     error
}

func (s *recordingStore) Record(_ context.Context, e *store.EventRecord) error {
	if s.err != nil {
		return s.err
	}
	s.records = append(s.records, e)
	return nil
}

// one requires exactly one persisted record and returns it.
func (s *recordingStore) one(t *testing.T) *store.EventRecord {
	t.Helper()
	require.Len(t, s.records, 1)
	return s.records[0]
}

func processorWith(monitor system.LogMonitor, events store.EventStore) *Processor {
	return NewProcessor(&Config{LogMonitor: monitor, Events: events})
}

// event builds the envelope the framework delivers. Data is left as a map because Process
// re-marshals it before decoding into the event type, exactly as it arrives from JetStream; Raw is
// the marshalled envelope, as it would be off the wire.
func event(subject string, data map[string]any) lifecycleagent.EventContext[lifecycleagent.CloudEvent[any]] {
	payload := lifecycleagent.CloudEvent[any]{
		SpecVersion: lifecycleagent.SpecVersion,
		ID:          "ce-1",
		Source:      "onboarding-api-7d56645cc7-2bzkl",
		Data:        data,
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		panic(err)
	}
	return lifecycleagent.EventContext[lifecycleagent.CloudEvent[any]]{
		Subject: subject,
		Payload: payload,
		Raw:     raw,
	}
}

func TestProcess_OnboardingStarted(t *testing.T) {
	monitor := &recordingMonitor{}
	events := &recordingStore{}

	err := processorWith(monitor, events).Process(context.Background(), event(SubjectOnboardingStarted, map[string]any{
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
	// And both land as ledger keys, which is what slice 2's correlation will run on.
	assert.Equal(t, store.CorrelationKeys{
		OnboardingProcessID: "proc-1",
		Bpn:                 "BPNL0000000001AB",
		HolderDid:           "did:web:identity.cxve.localhost:acme",
	}, events.one(t).Keys)
}

func TestProcess_OnboardingCompleted(t *testing.T) {
	monitor := &recordingMonitor{}
	events := &recordingStore{}

	err := processorWith(monitor, events).Process(context.Background(), event(SubjectOnboardingCompleted, map[string]any{
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
	// The completed event is the only onboarding event that knows the participant context.
	assert.Equal(t, store.CorrelationKeys{
		OnboardingProcessID:  "proc-1",
		Bpn:                  "BPNL0000000001AB",
		HolderDid:            "did:web:identity.cxve.localhost:acme",
		ParticipantContextID: "pctx-9",
	}, events.one(t).Keys)
}

func TestProcess_OnboardingCompleted_ReportsANonSuccessfulOutcomeAsSuch(t *testing.T) {
	// Both legs share one subject, so a tracker that routed on the subject alone would record this
	// failed onboarding as a completed one.
	for _, outcome := range []OnboardingState{OnboardingStateFailed, OnboardingStateRejected} {
		t.Run(string(outcome), func(t *testing.T) {
			monitor := &recordingMonitor{}
			events := &recordingStore{}

			err := processorWith(monitor, events).Process(context.Background(), event(SubjectOnboardingCompleted, map[string]any{
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
			// A terminal failure is still ledger material — with the keys it does have.
			assert.Equal(t, store.CorrelationKeys{OnboardingProcessID: "proc-2", Bpn: "BPNL0000000001AB"},
				events.one(t).Keys)
		})
	}
}

func TestProcess_EveryFamily_PersistsItsCorrelationKeys(t *testing.T) {
	// One representative leaf per family: what makes the ledger correlatable is that each family's
	// keys are promoted to columns, so a family whose case forgets its key would store its events
	// unattributable.
	pctxOnly := store.CorrelationKeys{ParticipantContextID: "pctx-1"}
	for subject, expected := range map[string]store.CorrelationKeys{
		SubjectAssetCreated:                pctxOnly,
		SubjectContractDefinitionCreated:   pctxOnly,
		SubjectContractNegotiationAgreed:   pctxOnly,
		SubjectPolicyDefinitionCreated:     pctxOnly,
		SubjectTransferProcessStarted:      pctxOnly,
		SubjectKeyPairAdded:                pctxOnly,
		SubjectParticipantContextCreated:   pctxOnly,
		SubjectCredentialOfferReceived:     pctxOnly,
		SubjectDidDocumentPublished:        {ParticipantContextID: "pctx-1", HolderDid: "did:web:acme"},
		SubjectIssuanceCredentialDelivered: {HolderDid: "holder-1"},
		// Secrets carry no attributable identity at all; the event is recorded keyless.
		SubjectSecretCreated: {},
	} {
		t.Run(subject, func(t *testing.T) {
			events := &recordingStore{}

			err := processorWith(&recordingMonitor{}, events).Process(context.Background(), event(subject, map[string]any{
				"participantContextId": "pctx-1",
				"did":                  "did:web:acme",
				"holderId":             "holder-1",
			}))

			require.NoError(t, err)
			assert.Equal(t, expected, events.one(t).Keys)
		})
	}
}

func TestProcess_PersistsTheRawEnvelope(t *testing.T) {
	// The ledger stores the message body as delivered, NOT a re-marshal of the typed envelope:
	// the typed form drops every member it does not declare (the onboarding events' sourcebpn and
	// participantdid extensions, for instance), and what was dropped could never be recovered.
	events := &recordingStore{}
	evt := event(SubjectOnboardingStarted, map[string]any{"processId": "proc-1"})
	evt.Raw = []byte(`{"id":"ce-1","sourcebpn":"BPNL0000000001AB","data":{"processId":"proc-1"}}`)

	require.NoError(t, processorWith(&recordingMonitor{}, events).Process(context.Background(), evt))

	record := events.one(t)
	assert.Equal(t, evt.Raw, record.Envelope)
	assert.Equal(t, "ce-1", record.EventID)
	assert.Equal(t, evt.Payload.Source, record.Source)
	assert.Equal(t, SubjectOnboardingStarted, record.Subject)
}

func TestProcess_MalformedPayload_IsPersistedWithoutKeys(t *testing.T) {
	// A payload the family struct cannot decode is still an event that happened. Dropping it (the
	// pre-ledger behaviour) would leave a hole in the record precisely where something went wrong —
	// so it is stored keyless and flagged, and the message is acked, not redelivered forever.
	for _, subject := range []string{SubjectOnboardingCompleted, SubjectIssuanceReceived, SubjectKeyPairRotated} {
		t.Run(subject, func(t *testing.T) {
			monitor := &recordingMonitor{}
			events := &recordingStore{}

			err := processorWith(monitor, events).Process(context.Background(), event(subject, map[string]any{
				"processId":            "proc-3",
				"bpn":                  42, // the wire type is a string
				"participantContextId": 42,
				"holderId":             42,
			}))

			require.NoError(t, err)
			require.Len(t, monitor.warns, 1)
			assert.Contains(t, monitor.warns[0], subject)
			assert.Equal(t, store.CorrelationKeys{}, events.one(t).Keys)
		})
	}
}

func TestProcess_DidDocumentUnpublished_IsNotReportedAsALink(t *testing.T) {
	// The published and unpublished leaves share one payload, so the family case alone cannot tell
	// them apart — and reporting an unpublish as a link would invert its meaning.
	monitor := &recordingMonitor{}

	err := processorWith(monitor, &recordingStore{}).Process(context.Background(), event(SubjectDidDocumentUnpublished, map[string]any{
		"did":                  "did:web:identity.cxve.localhost:acme",
		"participantContextId": "pctx-9",
	}))

	require.NoError(t, err)
	assert.Len(t, monitor.infos, 1) // the generic "Event received" line only
}

func TestProcess_UnknownFamily_IsPersistedKeyless(t *testing.T) {
	// The agent subscribes to events.> , so it sees families nothing here routes on. They must be
	// recorded (the ledger claims completeness) and acked — an error would back the stream up
	// behind them.
	monitor := &recordingMonitor{}
	events := &recordingStore{}

	err := processorWith(monitor, events).Process(context.Background(), event("events.somethingnew.created", map[string]any{
		"participantContextId": "pctx-1",
	}))

	require.NoError(t, err)
	assert.Equal(t, store.CorrelationKeys{}, events.one(t).Keys)
	assert.Empty(t, monitor.warns)
}

func TestProcess_StoreFailure_IsRecoverable(t *testing.T) {
	// Interest retention drops an event the moment it is acked, so acking on a failed write would
	// lose it forever. The error must be recoverable — NAK, redeliver, and survive the database
	// being down (or already closed during shutdown, which finalizes the pool before the loop).
	events := &recordingStore{err: errors.New("sql: database is closed")}

	err := processorWith(&recordingMonitor{}, events).Process(context.Background(),
		event(SubjectOnboardingStarted, map[string]any{"processId": "proc-1"}))

	require.Error(t, err)
	assert.True(t, types.IsRecoverable(err))
}
