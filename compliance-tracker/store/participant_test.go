package store

import (
	"context"
	"database/sql"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

var t0 = time.Date(2026, 8, 18, 12, 0, 0, 0, time.UTC)

func participant(processID, did string) *Participant {
	return &Participant{
		ProcessID:  processID,
		ExternalID: "ext-" + processID,
		Did:        did,
		Bpn:        "BPNL0000000001AB",
		StartedAt:  t0,
	}
}

type participantRow struct {
	did, bpn, pctx sql.NullString
	state          string
	startedAt      time.Time
	completedAt    sql.NullTime
}

func readParticipant(t *testing.T, processID string) participantRow {
	t.Helper()
	var r participantRow
	require.NoError(t, testDB.QueryRow(`
		SELECT did, bpn, participant_context_id, state, started_at, completed_at
		FROM participant WHERE process_id = $1`, processID).
		Scan(&r.did, &r.bpn, &r.pctx, &r.state, &r.startedAt, &r.completedAt))
	return r
}

func TestParticipant_OpenAndClose_RoundTrip(t *testing.T) {
	sut := newPostgresParticipantStore(testDB)

	require.NoError(t, sut.Open(context.Background(), participant("proc-rt", "did:web:rt")))
	opened := readParticipant(t, "proc-rt")
	assert.Equal(t, "RUNNING", opened.state)
	assert.True(t, opened.startedAt.Equal(t0))
	assert.False(t, opened.completedAt.Valid)

	require.NoError(t, sut.Close(context.Background(), &ParticipantClosure{
		ProcessID:            "proc-rt",
		ParticipantContextID: "pctx-rt",
		State:                "COMPLETED",
		CompletedAt:          t0.Add(10 * time.Minute),
	}))
	closed := readParticipant(t, "proc-rt")
	assert.Equal(t, "COMPLETED", closed.state)
	assert.Equal(t, "pctx-rt", closed.pctx.String)
	// Values the closure did not carry stay as learned — the started event's identities.
	assert.Equal(t, "did:web:rt", closed.did.String)
	assert.Equal(t, "BPNL0000000001AB", closed.bpn.String)
	assert.True(t, closed.completedAt.Time.Equal(t0.Add(10*time.Minute)))
}

func TestParticipant_RedeliveredStarted_DoesNotReopenAClosedRegistration(t *testing.T) {
	// Delivery is at-least-once and the started event may arrive again AFTER the completed one;
	// re-opening would resurrect a terminal registration and re-widen its attribution window.
	sut := newPostgresParticipantStore(testDB)
	require.NoError(t, sut.Open(context.Background(), participant("proc-redeliver", "did:web:redeliver")))
	require.NoError(t, sut.Close(context.Background(), &ParticipantClosure{
		ProcessID: "proc-redeliver", State: "REJECTED", CompletedAt: t0.Add(time.Minute),
	}))

	require.NoError(t, sut.Open(context.Background(), participant("proc-redeliver", "did:web:redeliver")))

	assert.Equal(t, "REJECTED", readParticipant(t, "proc-redeliver").state)
}

func TestParticipant_Link_ReachesOnlyTheRunningRegistration(t *testing.T) {
	// A rejected duplicate carries the DID of the participant it duplicated; the link must land
	// on the running registration, not the dead row.
	sut := newPostgresParticipantStore(testDB)
	require.NoError(t, sut.Open(context.Background(), participant("proc-link-dup", "did:web:link")))
	require.NoError(t, sut.Close(context.Background(), &ParticipantClosure{
		ProcessID: "proc-link-dup", State: "REJECTED", CompletedAt: t0,
	}))
	require.NoError(t, sut.Open(context.Background(), participant("proc-link-run", "did:web:link")))

	require.NoError(t, sut.LinkParticipantContext(context.Background(), "did:web:link", "pctx-link"))
	// A context outside any onboarding matches nothing — and is not an error.
	require.NoError(t, sut.LinkParticipantContext(context.Background(), "did:web:nobody", "pctx-x"))

	assert.Equal(t, "pctx-link", readParticipant(t, "proc-link-run").pctx.String)
	assert.False(t, readParticipant(t, "proc-link-dup").pctx.Valid)
}

func TestParticipant_Close_WithoutAnOpen_RecordsWhatItKnows(t *testing.T) {
	// The tracker may have started mid-flight: the closure is then the first (and only) thing it
	// hears of the registration. The window is zero-width — honestly, since nothing during the
	// registration's lifetime made it into the ledger either.
	sut := newPostgresParticipantStore(testDB)

	require.NoError(t, sut.Close(context.Background(), &ParticipantClosure{
		ProcessID: "proc-orphan", Did: "did:web:orphan", Bpn: "BPNL0000000002CD",
		ParticipantContextID: "pctx-orphan", State: "COMPLETED", CompletedAt: t0,
	}))

	r := readParticipant(t, "proc-orphan")
	assert.Equal(t, "COMPLETED", r.state)
	assert.Equal(t, "did:web:orphan", r.did.String)
	assert.True(t, r.startedAt.Equal(r.completedAt.Time))
}

func TestParticipant_HasDid(t *testing.T) {
	sut := newPostgresParticipantStore(testDB)
	require.NoError(t, sut.Open(context.Background(), participant("proc-known", "did:web:known")))

	known, err := sut.HasDid(context.Background(), "did:web:known")
	require.NoError(t, err)
	assert.True(t, known)
	unknown, err := sut.HasDid(context.Background(), "did:web:never-seen")
	require.NoError(t, err)
	assert.False(t, unknown)
}

// ---------------------------------------------------------------------------------------------
// participant_event view — the read-time correlation
// ---------------------------------------------------------------------------------------------

// viewEvent inserts a ledger event carrying the given keys at the given occurrence time.
func viewEvent(t *testing.T, source, id string, at time.Time, keys CorrelationKeys) {
	t.Helper()
	require.NoError(t, newPostgresEventStore(testDB).Record(context.Background(), &EventRecord{
		Source: source, EventID: id, Subject: "events.test", Type: "test",
		OccurredAt: at, Envelope: []byte(`{}`), Keys: keys,
	}))
}

// attributions returns the participants the view attributes the event to.
func attributions(t *testing.T, source, id string) []string {
	t.Helper()
	rows, err := testDB.Query(
		`SELECT participant_id FROM participant_event WHERE source = $1 AND event_id = $2`, source, id)
	require.NoError(t, err)
	defer rows.Close()
	var participants []string
	for rows.Next() {
		var p string
		require.NoError(t, rows.Scan(&p))
		participants = append(participants, p)
	}
	require.NoError(t, rows.Err())
	return participants
}

func TestParticipantEventView_AttributesIdentityEventsWithinTheWindow(t *testing.T) {
	sut := newPostgresParticipantStore(testDB)
	require.NoError(t, sut.Open(context.Background(), participant("proc-view-win", "did:web:view-win")))
	require.NoError(t, sut.LinkParticipantContext(context.Background(), "did:web:view-win", "pctx-view-win"))

	viewEvent(t, "view-win", "by-did", t0.Add(time.Minute), CorrelationKeys{HolderDid: "did:web:view-win"})
	viewEvent(t, "view-win", "by-pctx", t0.Add(time.Minute), CorrelationKeys{ParticipantContextID: "pctx-view-win"})
	// Before the registration started — a previous life of the identity, not this participant.
	viewEvent(t, "view-win", "too-early", t0.Add(-time.Hour), CorrelationKeys{HolderDid: "did:web:view-win"})
	// No keys at all: present in the ledger, absent from the view.
	viewEvent(t, "view-win", "keyless", t0.Add(time.Minute), CorrelationKeys{})

	assert.Equal(t, []string{"proc-view-win"}, attributions(t, "view-win", "by-did"))
	assert.Equal(t, []string{"proc-view-win"}, attributions(t, "view-win", "by-pctx"))
	assert.Empty(t, attributions(t, "view-win", "too-early"))
	assert.Empty(t, attributions(t, "view-win", "keyless"))
}

func TestParticipantEventView_ACompletedParticipantOwnsItsIdentityForever(t *testing.T) {
	// COMPLETED does not end the window: the participant exists permanently with this identity
	// (the duplicate check bars re-registration), so post-onboarding activity — assets,
	// contracts, transfers, key rotations — keeps attributing to it, however much later.
	sut := newPostgresParticipantStore(testDB)
	require.NoError(t, sut.Open(context.Background(), participant("proc-view-done", "did:web:view-done")))
	require.NoError(t, sut.Close(context.Background(), &ParticipantClosure{
		ProcessID: "proc-view-done", State: "COMPLETED", CompletedAt: t0.Add(10 * time.Minute),
	}))

	viewEvent(t, "view-done", "much-later", t0.Add(24*time.Hour), CorrelationKeys{HolderDid: "did:web:view-done"})

	assert.Equal(t, []string{"proc-view-done"}, attributions(t, "view-done", "much-later"))
}

func TestParticipantEventView_ADeadRegistrationsWindowCloses(t *testing.T) {
	// REJECTED/FAILED free the identifiers for re-registration: events after the terminal event
	// belong to whatever comes next, not to the dead registration attempt.
	sut := newPostgresParticipantStore(testDB)
	require.NoError(t, sut.Open(context.Background(), participant("proc-view-dead", "did:web:view-dead")))
	require.NoError(t, sut.Close(context.Background(), &ParticipantClosure{
		ProcessID: "proc-view-dead", State: "FAILED", CompletedAt: t0.Add(10 * time.Minute),
	}))

	viewEvent(t, "view-dead", "during", t0.Add(5*time.Minute), CorrelationKeys{HolderDid: "did:web:view-dead"})
	viewEvent(t, "view-dead", "after", t0.Add(time.Hour), CorrelationKeys{HolderDid: "did:web:view-dead"})

	assert.Equal(t, []string{"proc-view-dead"}, attributions(t, "view-dead", "during"))
	assert.Empty(t, attributions(t, "view-dead", "after"))
}

func TestParticipantEventView_OnboardingEventsAttributeByProcessIdOnly(t *testing.T) {
	// The rejected-duplicate scenario: the duplicate's own events carry the duplicated DID, and
	// its started/completed events fall inside the live participant's open window — matching by
	// identity would leak them into the participant they duplicated.
	sut := newPostgresParticipantStore(testDB)
	require.NoError(t, sut.Open(context.Background(), participant("proc-view-run", "did:web:view-dup")))
	require.NoError(t, sut.Open(context.Background(), participant("proc-view-dup", "did:web:view-dup")))
	require.NoError(t, sut.Close(context.Background(), &ParticipantClosure{
		ProcessID: "proc-view-dup", State: "REJECTED", CompletedAt: t0.Add(time.Second),
	}))

	viewEvent(t, "view-dup", "dup-completed", t0.Add(time.Second),
		CorrelationKeys{OnboardingProcessID: "proc-view-dup", HolderDid: "did:web:view-dup"})

	assert.Equal(t, []string{"proc-view-dup"}, attributions(t, "view-dup", "dup-completed"))
}
