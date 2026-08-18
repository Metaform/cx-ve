# compliance-tracker

A CFM **lifecycle agent**: a standalone service that consumes domain lifecycle events delivered as
[CloudEvents](https://cloudevents.io) v1.0 over NATS JetStream, built on
`github.com/eclipse-cfm/cfm/common/lifecycleagent`. It is the same framework as CFM's own
`agent/lifecycle/keymanagementagent`, and distinct from the *orchestration* agents under
`agent/orchestration/*` (such as the certo agent this VE deploys), which react to Provision
Manager activity messages instead.

> **Status: ledger, no rules yet.** The framework wiring, configuration, event decoding, subject
> dispatch, error semantics and the Postgres event ledger are complete and tested. The compliance
> rules themselves are not — the handlers in `handler/handler.go` extract, persist and return.

## Layout

| Path | Contents |
|---|---|
| `cmd/server/` | binary entrypoint |
| `launcher/` | framework wiring: agent name, subjects, config prefix, assemblies, processor construction |
| `handler/` | the `EventProcessor`: subject routing, correlation-key extraction, the (stubbed) compliance rules |
| `store/` | the event ledger: Postgres schema, `EventStore`, service assembly |

## Building and testing

```shell
go build ./...
go vet ./...
make test-unit        # handler tests; no Docker needed
make test             # all tests; the launcher and store suites start NATS/Postgres testcontainers
docker build -t compliance-tracker .
```

The image is published by `.github/workflows/publish.yml` to
`ghcr.io/metaform/cx-ve/compliance-tracker`.

## Configuration

Loaded by viper under the `compliancetracker` prefix, from environment variables (prefix + key,
dots replaced by underscores, uppercased) and/or a file named `compliancetracker.<yaml|json|env|…>`
in `/etc/appname`, `$HOME/.appname` or the working directory. A missing required value panics at
startup so the container restarts until it is configured.

| Env var | Required | Meaning |
|---|---|---|
| `COMPLIANCETRACKER_URI` | yes | NATS URL |
| `COMPLIANCETRACKER_BUCKET` | yes | JetStream KV bucket |
| `COMPLIANCETRACKER_STREAM` | yes | JetStream stream to bind to |
| `COMPLIANCETRACKER_POSTGRES_DSN` | yes | Postgres DSN of the event ledger |
| `COMPLIANCETRACKER_SUBJECTS` / `_SUBJECT` | no | merged with `launcher.DefaultSubjects` |
| `COMPLIANCETRACKER_CREATESTREAM` | no | create the stream instead of requiring it (default `false`) |
| `COMPLIANCETRACKER_NATS_AUTH_METHOD` | no | `none` \| `userinfo` \| `token` \| `nkey` \| `credentials` |
| `COMPLIANCETRACKER_NATS_AUTH_NKEYSEEDFILE` | no | NKey seed path, for `method=nkey` |

The runtime mode is selected with the `-mode` flag (`development` \| `production` \| `debug`).

### Subjects

The agent subscribes to `events.>` by default, and `handler.Process` dispatches on the event
**family** — the `SubjectPrefix*` constants, e.g. `events.issuance.` — decoding the family's base
struct rather than a per-occurrence one, so a leaf added upstream needs no new case. Configured
subjects are **merged** with `launcher.DefaultSubjects` rather than replacing
them, so configuration can only widen the subscription — and because the default is already a
catch-all, *any* configured subject overlaps it and makes NATS reject the consumer at startup with
`consumer subject filters cannot overlap`. To subscribe to something narrower, edit
`launcher.DefaultSubjects`.

Two of the routed families are not EDC events. The `events.onboarding.*` subjects come from
cx-ve's own Onboarding API and the `events.certificate.exchange.*` subjects from Certo (CX-0135
certificate exchange); both follow the CX-0000 §2.3 reverse-DNS `type` convention rather than
EDC's Java-class-name one — see the header of `handler/event_types.go`, which documents all three
producers. A certificate exchange event carries the publishing side's own participant context
(its correlation key) plus the counterparty's BPN and DID, which deliberately do NOT become keys:
Certo publishes each side of an exchange separately, so promoting the counterparty would
attribute one side's activity to the other. The onboarding started event is where a BPN and a DID first
appear together, before any provisioning — which is what lets the participant-context and issuance
events that follow be attributed to a partner. Caveat: the BPN is optional on registration, so a
started event may carry only the DID; the assigned BPN then arrives on the completed event. The
completed event is published for **every** terminal outcome, so its `state` field (`COMPLETED`,
`REJECTED` or `FAILED`) has to be inspected; the subject alone does not mean success.

## Event handling and the ledger

Every received event is appended to the `event` table of the configured Postgres database — the
`edc-events` stream is memory storage with interest retention, so once a message is acknowledged
the ledger row is its only remaining trace. Each row stores the **raw** CloudEvent envelope as
JSONB (the typed structs drop members they do not declare; the ledger must not inherit that loss)
plus the correlation keys the event's family carries, promoted to indexed columns:
`participant_context_id`, `holder_did`, `bpn` and `onboarding_process_id`. The primary key is
`(source, event_id)` — CloudEvents scope id uniqueness to the producer — and inserts are
`ON CONFLICT DO NOTHING`, which is what makes at-least-once delivery record-exactly-once.

### Correlating events to participants

Correlation is built purely from the event stream — never from the Onboarding API's database; the
tracker is an independent observer. A `participant` table registers one row per participant (or
failed registration attempt), unifying the three identities that never occur together in a single
event — BPN, DID and participant context id — learned from the onboarding lifecycle; the
onboarding process id is the row's primary key as the participant's provenance:

| Event | What it teaches the registry |
|---|---|
| `events.onboarding.started` | registers the participant: `process_id ↔ did` (and `↔ bpn` when submitted) |
| `events.diddocument.published` | links `did ↔ participant_context_id` — the one event carrying both |
| `events.onboarding.completed` | confirms the final identities, closes the registration with its terminal state |

Events are attributed to participants at **read time** by the `participant_event` view, so the
ledger stays immutable and identity knowledge learned late applies to history automatically.
Attribution rules: an event carrying an onboarding process id attributes to exactly the
participant that process created (a rejected duplicate's events carry the duplicated DID and must
not leak into the participant they duplicated); any other event attributes by identity — holder
DID or participant context id. A live participant (`RUNNING` or `COMPLETED`) owns its identity
permanently, so ALL of its activity attributes to it — a contract negotiation or a key rotation
years after onboarding included. The time window is the exception, fencing off `REJECTED`/`FAILED`
registration attempts: a dead attempt frees its identifiers for re-registration, so only events
between its start and its terminal event may attribute to it. Events matching no participant (the
operator's own contexts, secrets) do not appear in the view but stay queryable in the `event`
table.

```sql
-- everything that concerns one participant, in order
SELECT subject, type, occurred_at FROM participant_event
WHERE participant_id = '<process-id>' ORDER BY COALESCE(occurred_at, recorded_at);
```

On top of it, the `participant_eventlog` view is the rollup: ONE row per participant, carrying
BPN, DID and participant context id alongside the whole history as a time-ordered JSONB array of
compact event summaries (`occurred_at`, `subject`, `type`, `source`, `event_id`; the full
envelope is one join away in the `event` table via source + event id). A participant appears from
the moment its onboarding starts, with an empty history.

```sql
-- one participant's whole story, by any of its identities
SELECT * FROM participant_eventlog WHERE bpn = '<bpn>';  -- or did = … or participant_context_id = …
```

As a diagnostic, an issuance event whose `holderId` matches no participant logs a warning:
correlation rests on the assumption that issuance holder ids are participant DIDs, and a mismatch
would mean it is silently broken.

Events are delivered at-least-once, so handlers must be idempotent. The value returned from
`Process` decides the message's fate:

| Return | Effect |
|---|---|
| `nil` | acknowledged |
| `types.NewRecoverableError(…)` / `NewRecoverableWrappedError(…)` | negatively acknowledged, redelivered |
| any other error | **fatal**: acknowledged and dropped |

Ledger completeness beats extraction: a payload the family struct cannot decode, or a family
nothing routes on, is still recorded — keyless, with a warning — rather than dropped. Only a
failed ledger write fails the message, and recoverably: the database being down is a reason to
have the event redelivered, not to acknowledge it into oblivion.

## Running locally

```shell
docker run -d --name ct-nats -p 4222:4222 nats:alpine -js
docker run -d --name ct-pg -p 5432:5432 -e POSTGRES_USER=event_tracker \
  -e POSTGRES_PASSWORD=event_tracker -e POSTGRES_DB=event_tracker postgres:17-alpine
# create the "edc-events" stream with subject events.> , then:
go run ./cmd/server -mode=development
```

with `COMPLIANCETRACKER_URI=nats://127.0.0.1:4222`, `COMPLIANCETRACKER_BUCKET=cfm-bucket`,
`COMPLIANCETRACKER_STREAM=edc-events` and
`COMPLIANCETRACKER_POSTGRES_DSN=postgres://event_tracker:event_tracker@127.0.0.1:5432/event_tracker?sslmode=disable`
exported. `launcher/launcher_test.go` does exactly this end to end and is the quickest way to see
the whole path exercised.

## Deployment

Deployed by the umbrella chart: `charts/cx-ve/templates/compliance-tracker.yaml`, configured under
`complianceTracker` in `charts/cx-ve/values.yaml`. A ConfigMap keyed `compliancetracker.env` is
mounted at `/etc/appname`, a Vault init container delivers the NATS NKey seed for
`nats.auth.nkeySeedFile`, and telemetry comes from `envFrom: telemetry-config`. The `event_tracker`
database lives in the platform's Postgres and is provisioned by its postgres-init job — the
CX-VE-specific entry in the `postgresql.databases` list in `charts/cx-ve/values.yaml`; the agent
creates its own schema at startup.

It runs under the platform's `cfm-agents` ServiceAccount rather than an identity of its own — that
component is already provisioned by the platform (Vault role `nats-cfm-agents`, seed at
`secret/nats/cfm-agents`) and its NATS user is permitted to `subscribe: ["events.>"]`, granted so
CFM's kmagent can consume the same `edc-events` stream. Nothing to seed from this release. That
permission set is also why the KV bucket must be `cfm-bucket`: it is the only `$KV.*` subject the
`cfm-agents` user may touch, and the framework opens the bucket on connect.

Disabling NATS auth is all-or-nothing across the release — `complianceTracker.natsAuth.enabled` is
switch 5 of 5, documented in the NATS authentication block at the top of `charts/cx-ve/values.yaml`.
