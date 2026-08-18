package launcher

import (
	"context"
	"database/sql"
	"encoding/json"
	"testing"
	"time"

	"github.com/eclipse-cfm/cfm/common/natsclient"
	"github.com/eclipse-cfm/cfm/common/natsfixtures"
	"github.com/eclipse-cfm/cfm/common/sqlstore"
	_ "github.com/lib/pq" // Register PostgreSQL driver
	"github.com/nats-io/nats.go/jetstream"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// Integration test for the agent's framework wiring. Requires a working Docker daemon: the NATS
// broker and the Postgres ledger are started as testcontainers.
const (
	testTimeout  = 30 * time.Second
	pollInterval = 100 * time.Millisecond
	bucket       = "cfm-bucket"
	streamName   = "edc-events"
	// Derived by the framework from AgentName: lowercased, spaces and dots replaced by dashes.
	durableName = "compliance-tracker"
)

func TestComplianceTracker_Integration(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), testTimeout)
	defer cancel()

	nt, err := natsfixtures.SetupNatsContainer(ctx, bucket)
	require.NoError(t, err)
	defer natsfixtures.TeardownNatsContainer(ctx, nt)

	pg, dsn, err := sqlstore.SetupTestContainer(t)
	require.NoError(t, err)
	defer pg.Terminate(ctx)

	// Viper uppercases the prefix and the key, so the environment variables must be uppercase.
	t.Setenv("COMPLIANCETRACKER_URI", nt.URI)
	t.Setenv("COMPLIANCETRACKER_BUCKET", bucket)
	t.Setenv("COMPLIANCETRACKER_STREAM", streamName)
	t.Setenv("COMPLIANCETRACKER_POSTGRES_DSN", dsn)
	// No COMPLIANCETRACKER_SUBJECTS override: the agent must work on its compiled-in defaults.
	// Note that any override is MERGED with those, so an overlapping one (e.g. "events.>") makes
	// NATS reject the consumer with "consumer subject filters cannot overlap".

	// The event stream is provisioned out-of-band in a real deployment (createStream defaults to
	// false), so it has to exist before the agent binds a consumer to it.
	_, err = nt.Client.JetStream.CreateOrUpdateStream(ctx, jetstream.StreamConfig{
		Name:      streamName,
		Retention: jetstream.InterestPolicy,
		Storage:   jetstream.MemoryStorage,
		Subjects:  []string{"events.>"},
	})
	require.NoError(t, err)

	shutdown := make(chan struct{})
	go LaunchAndWaitSignal(shutdown)
	defer close(shutdown)

	require.Eventually(t, func() bool {
		stream, err := nt.Client.JetStream.Stream(ctx, streamName)
		if err != nil {
			return false
		}
		_, err = stream.Consumer(ctx, durableName)
		return err == nil
	}, testTimeout, pollInterval, "agent should create a durable consumer named %q", durableName)

	stream, err := nt.Client.JetStream.Stream(ctx, streamName)
	require.NoError(t, err)
	consumer, err := stream.Consumer(ctx, durableName)
	require.NoError(t, err)
	assert.ElementsMatch(t, DefaultSubjects, consumer.CachedInfo().Config.FilterSubjects)

	// Publish only once the consumer exists: under InterestPolicy a message with no interested
	// consumer is discarded immediately.
	payload, err := json.Marshal(map[string]any{
		"specversion": "1.0",
		"id":          "ce-1",
		"source":      "/cxve/test",
		"type":        "io.cfm.issuance.delivered",
		"data":        map[string]string{"holderId": "did:web:acme", "id": "credential-1"},
	})
	require.NoError(t, err)

	_, err = natsclient.NewMsgClient(nt.Client).Publish(ctx, "events.issuance.delivered", payload)
	require.NoError(t, err)

	// The event is consumed and acknowledged, i.e. the processor returned nil.
	assert.Eventually(t, func() bool {
		stream, err := nt.Client.JetStream.Stream(ctx, streamName)
		if err != nil {
			return false
		}
		consumer, err := stream.Consumer(ctx, durableName)
		if err != nil {
			return false
		}
		info := consumer.CachedInfo()
		return info.NumPending == 0 && info.NumAckPending == 0
	}, testTimeout, pollInterval, "published event should be consumed and acknowledged")

	// Acknowledged MUST mean persisted: the stream's interest retention drops the message on ack,
	// so the ledger row is the only remaining trace — assert the whole pipeline down to the
	// promoted key and the raw envelope.
	db, err := sql.Open("postgres", dsn)
	require.NoError(t, err)
	defer db.Close()

	var subject, eventType, holderDid, envelope string
	require.NoError(t, db.QueryRow(`
		SELECT subject, type, holder_did, envelope FROM event
		WHERE source = '/cxve/test' AND event_id = 'ce-1'`).
		Scan(&subject, &eventType, &holderDid, &envelope))
	assert.Equal(t, "events.issuance.delivered", subject)
	assert.Equal(t, "io.cfm.issuance.delivered", eventType)
	assert.Equal(t, "did:web:acme", holderDid)
	assert.JSONEq(t, string(payload), envelope)
}
