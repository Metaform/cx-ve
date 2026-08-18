package store

import (
	"testing"

	"github.com/eclipse-cfm/cfm/common/system"
	"github.com/spf13/viper"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func newInitContext(dsn string) *system.InitContext {
	config := viper.New()
	if dsn != "" {
		config.Set(DsnKey, dsn)
	}
	return &system.InitContext{
		StartContext: system.StartContext{
			Registry:   system.NewServiceRegistry(),
			LogMonitor: system.NoopMonitor{},
			Config:     config,
			Mode:       system.DevelopmentMode,
		},
	}
}

func TestPostgresServiceAssembly_Init_RegistersTheStore(t *testing.T) {
	// Init runs the DDL against a schema TestMain already created, so this also proves the DDL is
	// idempotent — which is what lets it run unconditionally at every startup.
	assembly := &PostgresServiceAssembly{}
	ictx := newInitContext(testDSN)

	require.NoError(t, assembly.Init(ictx))
	defer func() { require.NoError(t, assembly.Finalize()) }()

	_, ok := ictx.Registry.Resolve(EventStoreKey).(EventStore)
	assert.True(t, ok)
}

func TestPostgresServiceAssembly_Init_FailsFastWithoutDsn(t *testing.T) {
	// The agreed contract: no DSN is a startup failure, never a silent log-only mode — a tracker
	// acknowledging events into nowhere would be a hole in the compliance record.
	assembly := &PostgresServiceAssembly{}

	err := assembly.Init(newInitContext(""))

	require.Error(t, err)
	assert.Contains(t, err.Error(), DsnKey)
	require.NoError(t, assembly.Finalize())
}

func TestPostgresServiceAssembly_Init_FailsFastWhenTheDatabaseIsUnreachable(t *testing.T) {
	// The driver connects lazily, so an unreachable database surfaces at the DDL — which must
	// abort assembly (crash-restart until the database exists) instead of leaving the agent
	// running broken.
	assembly := &PostgresServiceAssembly{}

	err := assembly.Init(newInitContext("postgres://x:x@127.0.0.1:1/none?sslmode=disable&connect_timeout=1"))

	require.Error(t, err)
	assert.Contains(t, err.Error(), "failed to create tables")
	require.NoError(t, assembly.Finalize())
}
