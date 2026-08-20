# Catena-X Membership Hub

Drives a partner's full path into the dataspace by combining the two halves the VE deliberately
keeps apart:

1. **Registration** — submits the partner to the Onboarding API (CX-0006), acting as an
   onboarding service provider (OAuth2 client-credentials against the VE's OSP IdP). The
   Onboarding API validates, assigns the BPN, proves identity and registers the credential
   holder with the IssuerService; its CONFIRMED status callback lands on this app.
2. **Provisioning** — once the submission returns with the registration CONFIRMED, creates a
   tenant and deploys the participant profile via the CFM Tenant Manager, which runs the VPA
   orchestration (connector, IdentityHub, Siglet, Certo — the registration agent is no longer
   part of it). The returned participant profile id is stored on the record; reading the member
   resolves it and fetches the profile's current state from the Tenant Manager — that is where
   the `participantContextId` appears and deployment errors surface.

The membership record correlates the two id spaces: the `externalId` this app mints (the key the
status callbacks carry) and the `participantContextId` provisioning assigns.

## API

| Endpoint | Purpose |
|---|---|
| `POST /api/members` | Submit a member (name, shortName, bpn, optional did, uniqueIds, companyRoles, agreements). Returns the membership record incl. its `externalId`. |
| `GET /api/members/{externalId}` | The correlated view. For a member with a deployed profile, resolves the stored profile id and reads its current state from the Tenant Manager. |
| `POST /api/callbacks/registration-status` | The status-callback endpoint registered with the Onboarding API. Not meant for humans. |

States: `SUBMITTED → CONFIRMED → PROVISIONING → PROVISIONED` (the happy path runs through within
the `POST`), with `REJECTED`/`FAILED` as terminal off-ramps and `REGISTERING` marking a
registration that did not confirm within the submitting call (such a record is never
provisioned). The BPN is required on ingress: the status callback does not carry an assigned BPN
back, and provisioning (the certo activity) needs it.

## Building and testing

```shell
./gradlew build            # unit tests included; no cluster needed
./gradlew e2eTest          # the VE's black-box e2e suite (src/e2e-test) — needs a RUNNING VE
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
