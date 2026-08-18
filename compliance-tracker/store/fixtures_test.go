package store

import (
	"context"
	"database/sql"
	"os"
	"testing"

	"github.com/eclipse-cfm/cfm/common/sqlstore"
	"github.com/testcontainers/testcontainers-go"
)

var (
	testContainer testcontainers.Container
	testDB        *sql.DB
	testDSN       string
)

// TestMain starts one Postgres container for the whole package (the CFM sqlstore test pattern) —
// these tests need a running Docker daemon, like the launcher suite. Tests isolate by using
// distinct event sources rather than truncating between runs.
func TestMain(m *testing.M) {
	ctx := context.Background()

	var err error
	testContainer, testDSN, err = sqlstore.SetupTestContainer(nil)
	if err != nil {
		panic(err)
	}

	testDB, err = sql.Open("postgres", testDSN)
	if err != nil {
		panic(err)
	}
	defer testDB.Close()

	if err = createTables(testDB); err != nil {
		panic(err)
	}

	code := m.Run()

	testContainer.Terminate(ctx)
	os.Exit(code)
}
