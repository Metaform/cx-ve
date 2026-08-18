package store

import (
	"context"
	"database/sql"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func record(source, id string) *EventRecord {
	return &EventRecord{
		Source:     source,
		EventID:    id,
		Subject:    "events.onboarding.started",
		Type:       "org.catena-x.onboarding.started.v1",
		OccurredAt: time.Date(2026, 8, 18, 12, 0, 0, 0, time.UTC),
		Envelope:   []byte(`{"id":"` + id + `","sourcebpn":"BPNL0000000001AB","data":{"processId":"proc-1"}}`),
		Keys: CorrelationKeys{
			ParticipantContextID: "pctx-1",
			HolderDid:            "did:web:acme",
			Bpn:                  "BPNL0000000001AB",
			OnboardingProcessID:  "proc-1",
		},
	}
}

func count(t *testing.T, source string) int {
	t.Helper()
	var n int
	require.NoError(t, testDB.QueryRow(`SELECT count(*) FROM event WHERE source = $1`, source).Scan(&n))
	return n
}

func TestRecord_RoundTripsEveryColumn(t *testing.T) {
	sut := newPostgresEventStore(testDB)
	require.NoError(t, sut.Record(context.Background(), record("roundtrip", "ce-1")))

	var (
		subject, eventType, envelope, pctx, did, bpn, process string
		occurredAt, recordedAt                                time.Time
	)
	require.NoError(t, testDB.QueryRow(`
		SELECT subject, type, occurred_at, recorded_at, envelope,
		       participant_context_id, holder_did, bpn, onboarding_process_id
		FROM event WHERE source = 'roundtrip' AND event_id = 'ce-1'`).
		Scan(&subject, &eventType, &occurredAt, &recordedAt, &envelope, &pctx, &did, &bpn, &process))

	assert.Equal(t, "events.onboarding.started", subject)
	assert.Equal(t, "org.catena-x.onboarding.started.v1", eventType)
	assert.True(t, occurredAt.Equal(time.Date(2026, 8, 18, 12, 0, 0, 0, time.UTC)))
	assert.False(t, recordedAt.IsZero())
	// The whole envelope survives as JSON — including the extension member (sourcebpn) that the
	// typed CloudEvent struct would have dropped, which is the reason the raw bytes are stored.
	assert.JSONEq(t, string(record("roundtrip", "ce-1").Envelope), envelope)
	assert.Equal(t, "pctx-1", pctx)
	assert.Equal(t, "did:web:acme", did)
	assert.Equal(t, "BPNL0000000001AB", bpn)
	assert.Equal(t, "proc-1", process)
}

func TestRecord_RedeliveryIsANoOp(t *testing.T) {
	// Delivery is at-least-once; the PK dedup is what lets the handler treat every nil return as
	// "safe to ack" without ever writing an event twice.
	sut := newPostgresEventStore(testDB)

	require.NoError(t, sut.Record(context.Background(), record("redelivery", "ce-1")))
	require.NoError(t, sut.Record(context.Background(), record("redelivery", "ce-1")))

	assert.Equal(t, 1, count(t, "redelivery"))
}

func TestRecord_IdUniquenessIsScopedToTheSource(t *testing.T) {
	// The CloudEvents spec scopes id uniqueness to the producer, so two runtimes may legitimately
	// use the same id — a PK on the id alone would silently drop one of the two events.
	sut := newPostgresEventStore(testDB)

	require.NoError(t, sut.Record(context.Background(), record("source-a", "ce-1")))
	require.NoError(t, sut.Record(context.Background(), record("source-b", "ce-1")))

	assert.Equal(t, 1, count(t, "source-a"))
	assert.Equal(t, 1, count(t, "source-b"))
}

func TestRecord_MissingId_FallsBackToTheEnvelopeDigest(t *testing.T) {
	// An id-less event must still dedupe deterministically on redelivery, and two DIFFERENT
	// id-less events must not collide on some shared placeholder.
	sut := newPostgresEventStore(testDB)
	first := record("no-id", "")
	require.NoError(t, sut.Record(context.Background(), first))
	require.NoError(t, sut.Record(context.Background(), record("no-id", ""))) // same envelope again

	other := record("no-id", "")
	other.Envelope = []byte(`{"data":{"processId":"proc-2"}}`)
	require.NoError(t, sut.Record(context.Background(), other))

	assert.Equal(t, 2, count(t, "no-id"))
	var id string
	require.NoError(t, testDB.QueryRow(
		`SELECT event_id FROM event WHERE source = 'no-id' AND envelope = $1::jsonb`,
		string(first.Envelope)).Scan(&id))
	assert.Contains(t, id, "sha256:")
}

func TestRecord_AbsentValuesAreNull(t *testing.T) {
	// Empty keys/time must land as NULL, not '': the partial indexes and slice 2's correlation
	// joins treat NULL as "not carried", while an empty string would join/match like a value.
	sut := newPostgresEventStore(testDB)
	e := record("nulls", "ce-1")
	e.OccurredAt = time.Time{}
	e.Keys = CorrelationKeys{}
	require.NoError(t, sut.Record(context.Background(), e))

	var occurredAt sql.NullTime
	var pctx, did, bpn, process sql.NullString
	require.NoError(t, testDB.QueryRow(`
		SELECT occurred_at, participant_context_id, holder_did, bpn, onboarding_process_id
		FROM event WHERE source = 'nulls'`).Scan(&occurredAt, &pctx, &did, &bpn, &process))

	assert.False(t, occurredAt.Valid)
	assert.False(t, pctx.Valid)
	assert.False(t, did.Valid)
	assert.False(t, bpn.Valid)
	assert.False(t, process.Valid)
}

func TestRecord_RejectsAnEmptyEnvelope(t *testing.T) {
	// Cannot happen for a message the framework decoded (Raw is the decoded body), so an empty
	// envelope means a caller bug — refused rather than stored as an empty ledger entry.
	sut := newPostgresEventStore(testDB)
	e := record("empty", "ce-1")
	e.Envelope = nil

	require.Error(t, sut.Record(context.Background(), e))
	assert.Equal(t, 0, count(t, "empty"))
}
