# certo-agent

A CFM **orchestration agent** for Certo (SCE certificate exchange), built on
`github.com/eclipse-cfm/cfm/pmanager/natsagent`. It subscribes to the Provision Manager's activity
stream on NATS and handles the `certo-activity`:

- **deploy** creates a participant context in Certo (`POST /management/v1/participant-contexts`)
  for the provisioned participant, carrying its DID and, when set, the `dataspaceIdentifier` from
  the participant's `cfm.issuer` VPA properties;
- **dispose** deletes that participant context again.

It authenticates to Certo's management API with a token exchanged at jwtlet
(scope `certo-mgmt-api:write`, resource `sudo`).

The agent used to live in the CFM repository (`agent/orchestration/certo`); it moved here because
Certo is specific to this VE. The `certo-activity` definition and its place in the VPA
orchestration are seeded by `charts/cx-ve/templates/certo-activity-seed-job.yaml`.

## Configuration

Read from `certoagent.env` (config prefix `certoagent`), as rendered by
`charts/cx-ve/templates/certo-agent.yaml`:

| Key | Meaning |
|---|---|
| `uri`, `bucket`, `stream` | NATS connection and the CFM activity stream |
| `certo.url` | Base URL of Certo's management API (required) |
| `tokenexchange.url`, `tokenexchange.audience`, `tokenexchange.tokenFilePath` | jwtlet token exchange |
| `nats.auth.method`, `nats.auth.nkeySeedFile` | Optional NKey auth for NATS |

## Build

```shell
go build ./...
make test
docker build -t certo-agent .
```
