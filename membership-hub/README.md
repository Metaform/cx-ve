# Catena-X Membership Hub

Drives a partner's full path into the dataspace by combining the two halves the VE deliberately
keeps apart:

1. **Registration** — submits the partner to the Onboarding API (CX-0006), acting as an
   onboarding service provider (OAuth2 client-credentials against the VE's OSP IdP). The
   Onboarding API validates, assigns the BPN, proves identity and registers the credential
   holder with the IssuerService; its CONFIRMED status callback lands on this app.
2. **Provisioning** — on CONFIRMED, creates a tenant and deploys the participant profile via the
   CFM Tenant Manager, which runs the VPA orchestration (connector, IdentityHub, Siglet, Certo —
   the registration agent is no longer part of it).

The membership record correlates the two id spaces: the `externalId` this app mints (the key the
status callbacks carry) and the `participantContextId` provisioning assigns.

## API

| Endpoint | Purpose |
|---|---|
| `POST /api/members` | Submit a member (name, shortName, bpn, optional did, uniqueIds, companyRoles, agreements). Returns the membership record incl. its `externalId`. |
| `GET /api/members/{externalId}` | The correlated view. Reading a PROVISIONING member refreshes it against the Tenant Manager (lazy poll — no background loop). |
| `POST /api/callbacks/registration-status` | The status-callback endpoint registered with the Onboarding API. Not meant for humans. |

States: `SUBMITTED → REGISTERING → PROVISIONING → PROVISIONED`, with `REJECTED`/`FAILED` as
terminal off-ramps. The BPN is required on ingress: the status callback does not carry an
assigned BPN back, and provisioning (the certo activity) needs it.

## Building and testing

```shell
./gradlew build            # unit tests included; no cluster needed
docker build -t membership-hub .
```

Run database-free with `SPRING_PROFILES_ACTIVE=test` (in-memory store; state lost on restart).
Postgres is the default (`spring.datasource.*`, database `membershiphub`).

## Configuration

See `src/main/resources/application.yaml` — every key is annotated with its environment-variable
override. The deployed configuration lives in `charts/membership-hub/values.yaml` (`config:` is
rendered 1:1 into the pod's application.yaml). Notable:

- `onboarding-api.*` — base URL, OSP OAuth2 client (must be seeded in the OSP IdP with the
  `configure_partner_registration` scope; the umbrella chart does this) and the callback URL this
  app registers.
- `tenant-manager.*` — base URL and the jwtlet mapping (`token-resource`) the workload token is
  exchanged under; the chart's jwtlet-seed job registers it with the
  `tenant-manager-api:read/write` scopes.
- `participant.*` — the DID template and the `cfm.dataplane`/CCM transfer-type mappings sent with
  the participant profile. The `cfm.issuer` VPA properties are always sent (the certo activity
  reads the BPN from them).

The image is published by `.github/workflows/publish.yml` to
`ghcr.io/metaform/cx-ve/membership-hub`.
