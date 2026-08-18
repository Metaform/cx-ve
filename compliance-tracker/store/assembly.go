package store

import (
	"database/sql"
	"fmt"

	"github.com/XSAM/otelsql"
	"github.com/eclipse-cfm/cfm/common/system"
	_ "github.com/lib/pq" // Register PostgreSQL driver
	semconv "go.opentelemetry.io/otel/semconv/v1.40.0"
)

const (
	driverName = "postgres"

	// DsnKey is the viper key holding the Postgres DSN — with the agent's config prefix that is
	// the env var COMPLIANCETRACKER_POSTGRES_DSN, or "postgres.dsn" in compliancetracker.env.
	DsnKey = "postgres.dsn"
)

// PostgresServiceAssembly owns the database pool and provides the EventStore. Modelled on CFM's
// tmanager sqlstore assembly; no TransactionContext is registered because the ledger is written
// in single, atomic inserts.
type PostgresServiceAssembly struct {
	system.DefaultServiceAssembly
	db *sql.DB
}

func (a *PostgresServiceAssembly) Name() string {
	return "Compliance Tracker Postgres"
}

func (a *PostgresServiceAssembly) Provides() []system.ServiceType {
	return []system.ServiceType{EventStoreKey, BindingStoreKey}
}

func (a *PostgresServiceAssembly) Init(ictx *system.InitContext) error {
	// A tracker without its ledger would acknowledge events into nowhere — the stream drops them
	// on ack — so an unconfigured DSN is a startup failure, not a degraded mode. The error aborts
	// assembly, which panics the runtime; the container then restarts until configuration (or the
	// database, below) is there.
	if !ictx.Config.IsSet(DsnKey) {
		return fmt.Errorf("missing Postgres DSN configuration: %s", DsnKey)
	}
	dsn := ictx.Config.GetString(DsnKey)

	db, err := otelsql.Open(driverName, dsn, otelsql.WithAttributes(semconv.DBSystemNamePostgreSQL))
	if err != nil {
		return fmt.Errorf("error connecting to DB: %w", err)
	}

	a.db = db
	otelsql.RegisterDBStatsMetrics(db, otelsql.WithAttributes(semconv.DBSystemNamePostgreSQL))

	if err := createTables(db); err != nil {
		return fmt.Errorf("failed to create tables: %w", err)
	}

	ictx.Registry.Register(EventStoreKey, newPostgresEventStore(db))
	ictx.Registry.Register(BindingStoreKey, newPostgresBindingStore(db))
	return nil
}

func (a *PostgresServiceAssembly) Finalize() error {
	// Assemblies finalize dependency-first, so this pool closes while the processing loop may
	// still be delivering. That is safe only because the handler reports store failures as
	// recoverable: the in-flight message is NAKed and redelivered after the restart.
	if a.db != nil {
		return a.db.Close()
	}
	return nil
}
