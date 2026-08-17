# compliance-tracker

A CFM **lifecycle agent**: a standalone service that consumes domain lifecycle events delivered as
[CloudEvents](https://cloudevents.io) v1.0 over NATS JetStream, built on
`github.com/eclipse-cfm/cfm/common/lifecycleagent`. It is the same framework as CFM's own
`agent/lifecycle/keymanagementagent`, and distinct from the *orchestration* agents under
`agent/orchestration/*` (such as the certo agent this VE deploys), which react to Provision
Manager activity messages instead.

> **Status: scaffold.** The framework wiring, configuration, event decoding, subject dispatch and
> error semantics are complete and tested. The compliance rules themselves are not — the two
> handlers in `handler/handler.go` log and return, each marking where the rule goes with a `TODO`.

## Layout

| Path | Contents |
|---|---|
| `cmd/server/` | binary entrypoint |
| `launcher/` | framework wiring: agent name, subjects, config prefix, processor construction |
| `handler/` | the `EventProcessor`: subject routing and the (stubbed) compliance rules |

## Building and testing

```shell
go build ./...
go vet ./...
make test-unit        # handler tests; no Docker needed
make test             # all tests; the launcher suite starts a NATS testcontainer
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

Two of the routed subjects are not EDC events: `events.onboarding.started` and
`events.onboarding.completed` come from cx-ve's own Onboarding API. Their CloudEvent `type` follows
the CX-0000 §2.3 reverse-DNS convention rather than EDC's Java-class-name one — see the header of
`handler/event_types.go`, which documents both. The started event is where a BPN and a DID first
appear together, before any provisioning — which is what lets the participant-context and issuance
events that follow be attributed to a partner. The completed event is published for **every**
terminal outcome, so its
`state` field (`COMPLETED`, `REJECTED` or `FAILED`) has to be inspected; the subject alone does not
mean success.

## Event handling

Events are delivered at-least-once, so handlers must be idempotent. The value returned from
`Process` decides the message's fate:

| Return | Effect |
|---|---|
| `nil` | acknowledged |
| `types.NewRecoverableError(…)` / `NewRecoverableWrappedError(…)` | negatively acknowledged, redelivered |
| any other error | **fatal**: acknowledged and dropped |

A payload that fails to unmarshal is also acknowledged and dropped, so every field of
`ComplianceEventData` is optional. Fields it does not model are still reachable: the framework
passes the undecoded message body as `EventContext.Raw`.

## Running locally

```shell
docker run -d --name ct-nats -p 4222:4222 nats:alpine -js
# create the "edc-events" stream with subject events.> , then:
go run ./cmd/server -mode=development
```

with `COMPLIANCETRACKER_URI=nats://127.0.0.1:4222`, `COMPLIANCETRACKER_BUCKET=cfm-bucket` and
`COMPLIANCETRACKER_STREAM=edc-events` exported. `launcher/launcher_test.go` does exactly this
end to end and is the quickest way to see the whole path exercised.

## Deployment

Deployed by the umbrella chart: `charts/cx-ve/templates/compliance-tracker.yaml`, configured under
`complianceTracker` in `charts/cx-ve/values.yaml`. A ConfigMap keyed `compliancetracker.env` is
mounted at `/etc/appname`, a Vault init container delivers the NATS NKey seed for
`nats.auth.nkeySeedFile`, and telemetry comes from `envFrom: telemetry-config`.

It runs under the platform's `cfm-agents` ServiceAccount rather than an identity of its own — that
component is already provisioned by the platform (Vault role `nats-cfm-agents`, seed at
`secret/nats/cfm-agents`) and its NATS user is permitted to `subscribe: ["events.>"]`, granted so
CFM's kmagent can consume the same `edc-events` stream. Nothing to seed from this release. That
permission set is also why the KV bucket must be `cfm-bucket`: it is the only `$KV.*` subject the
`cfm-agents` user may touch, and the framework opens the bucket on connect.

Disabling NATS auth is all-or-nothing across the release — `complianceTracker.natsAuth.enabled` is
switch 5 of 5, documented in the NATS authentication block at the top of `charts/cx-ve/values.yaml`.
