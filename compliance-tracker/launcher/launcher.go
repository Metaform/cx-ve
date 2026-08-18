// Package launcher wires the compliance tracker onto CFM's lifecycle agent framework.
//
// Configuration is loaded by viper under the "compliancetracker" prefix, either from environment
// variables (prefix + key, dots replaced by underscores, uppercased) or from a file named
// compliancetracker.<yaml|json|env|...> in /etc/appname, $HOME/.appname or the working directory:
//
//	COMPLIANCETRACKER_URI                       (required) NATS URL
//	COMPLIANCETRACKER_BUCKET                    (required) JetStream KV bucket
//	COMPLIANCETRACKER_STREAM                    (required) JetStream stream to bind to
//	COMPLIANCETRACKER_POSTGRES_DSN              (required) Postgres DSN of the event ledger
//	COMPLIANCETRACKER_SUBJECTS / _SUBJECT       (optional) merged with DefaultSubjects
//	COMPLIANCETRACKER_CREATESTREAM              (optional) create the stream instead of requiring it
//	COMPLIANCETRACKER_NATS_AUTH_METHOD          (optional) none|userinfo|token|nkey|credentials
//	COMPLIANCETRACKER_NATS_AUTH_NKEYSEEDFILE    (optional) NKey seed path, for method=nkey
//
// A missing required value panics at startup so the container restarts until it is configured.
// The runtime mode is selected with the -mode flag (development|production|debug).
package launcher

import (
	"github.com/eclipse-cfm/cfm/common/lifecycleagent"
	"github.com/eclipse-cfm/cfm/common/system"
	"github.com/metaform/cx-ve/compliance-tracker/handler"
	"github.com/metaform/cx-ve/compliance-tracker/store"
)

const (
	// AgentName also seeds the durable consumer name: the framework lowercases it and replaces
	// spaces and dots with dashes, yielding "compliance-tracker". Changing it orphans the
	// existing durable consumer.
	AgentName = "Compliance Tracker"

	// ServiceName is the name reported to telemetry.
	ServiceName = "cxve.agent.compliancetracker"

	// ConfigPrefix is the viper prefix, and therefore both the environment variable prefix and
	// the base name of the config file the agent looks for.
	ConfigPrefix = "compliancetracker"
)

// DefaultSubjects are the event families the compliance rules are built on — the same two the
// handler routes on. Deliberately not a catch-all ("events.>"): CFM's lifecycle-agent decision
// record discourages those, and more concretely NATS rejects a consumer whose filter subjects
// overlap. Because the framework MERGES these with COMPLIANCETRACKER_SUBJECTS rather than
// replacing them, configuration can only widen the subscription, and any configured subject that
// overlaps one of these (a catch-all, or a leaf like "events.issuance.delivered") fails at
// startup with "consumer subject filters cannot overlap". To subscribe to something narrower,
// change this slice.
var DefaultSubjects = []string{"events.>"}

// LaunchAndWaitSignal starts the agent and blocks until the shutdown channel is closed.
func LaunchAndWaitSignal(shutdown <-chan struct{}) {
	config := lifecycleagent.LauncherConfig[lifecycleagent.CloudEvent[any]]{
		AgentName:    AgentName,
		ServiceName:  ServiceName,
		ConfigPrefix: ConfigPrefix,
		Subjects:     DefaultSubjects,
		// Everything an assembly Provides() is initialized before the agent starts consuming, so
		// the store is up (or startup has failed fast) before the first event arrives. A future
		// HTTP dependency registers its assembly here too — see the CFM keymanagementagent
		// launcher for the httpclient + token-provider wiring.
		AssemblyProvider: func() []system.ServiceAssembly {
			return []system.ServiceAssembly{&store.PostgresServiceAssembly{}}
		},
		NewProcessor: func(ctx *lifecycleagent.AgentContext) lifecycleagent.EventProcessor[lifecycleagent.CloudEvent[any]] {
			return handler.NewProcessor(&handler.Config{
				LogMonitor:   ctx.Monitor,
				Events:       ctx.Registry.Resolve(store.EventStoreKey).(store.EventStore),
				Participants: ctx.Registry.Resolve(store.ParticipantStoreKey).(store.ParticipantStore),
			})
		},
	}
	lifecycleagent.LaunchAgent(shutdown, config)
}
