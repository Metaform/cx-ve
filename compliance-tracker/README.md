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

The agent subscribes to `events.issuance.>` and `events.keypair.>` by default. Configured subjects
are **merged** with those defaults rather than replacing them, so configuration can only widen the
subscription — and any configured subject that *overlaps* a default (a catch-all `events.>`, or a
leaf like `events.issuance.delivered`) makes NATS reject the consumer at startup with
`consumer subject filters cannot overlap`. To subscribe to something narrower, edit
`launcher.DefaultSubjects`.

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

Not deployed yet — there is no Helm template for it. When you add one,
`charts/cx-ve/templates/certo-agent.yaml` is the model: a ConfigMap keyed `compliancetracker.env`
mounted at `/etc/appname`, a Vault init container delivering the NATS NKey seed for
`nats.auth.nkeySeedFile`, and `envFrom: telemetry-config`.
