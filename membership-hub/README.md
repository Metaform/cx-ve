# Membership Hub

Drives a partner's full path into one of the dataspaces this VE hosts (Catena-X; Decade-X through
its TSP onboarding intake) by combining the two halves the VE deliberately keeps apart:

1. **Provisioning** — for a member this VE hosts, creates a tenant and deploys the participant
   profile via the CFM Tenant Manager, which runs the VPA orchestration (connector, IdentityHub,
   Siglet, Certo — neither credential activity is part of it). The returned participant profile
   id is stored on the record; reading the member resolves it and fetches the profile's current
   state from the Tenant Manager — that is where the `participantContextId` appears and
   deployment errors surface.
2. **Registration** — once the participant context exists, submits the partner to its
   dataspace's onboarding API, acting as an onboarding service provider (OAuth2
   client-credentials against the VE's OSP IdP). For Catena-X that is the `cx-onboarding-api`
   (CX-0006/CX-0009): it validates, proves identity, registers the credential holder with the
   IssuerService and has it offer the member its credentials; its CONFIRMED status callback lands
   on this app and is the membership's terminal success.

Everything dataspace-specific sits behind one `DataspaceOnboarding` implementation per dataspace
(`adapter/out/onboarding/<dataspace>`): the shape of the request's `registration` object, the
onboarding API's endpoints, payloads and authentication, how it reports a registration's outcome
(a status callback in its own wire format — or, for an API without callbacks like Decade-X's, a
status the hub polls every `participant.registration.poll-interval`), and the `cfm.issuer` properties of a hosted member's participant profile. The choreography itself
is the same for every dataspace.

Deployment comes first because the credential offer is PUSHED to the credential service the
member's DID document advertises — the wallet has to exist by the time the registration runs.

The membership record correlates the two id spaces: the `externalId` this app mints (the key the
status callbacks carry) and the `participantContextId` provisioning assigns.

## API

| Endpoint | Purpose |
|---|---|
| `GET /api/dataspaces` | The dataspaces members can be onboarded into (`id`, `displayName`) — the enabled entries of `dataspaces.*`. |
| `POST /api/members` | Submit a member: `dataspace`, `name`, `shortName`, `memberId` (the id within the dataspace — the BPN in Catena-X, required; the Decade-X-ID in Decade-X, required for a member hosted here and optional for an external one — the TSP honors a declared id and assigns one on approval otherwise), optional `did`, and the dataspace-specific `registration` object (Catena-X: city, streetName, countryAlpha2Code, region, uniqueIds, companyRoles, agreements, userDetails; Decade-X: the TSP's onboarding request — `legalEntity` (without `legalName`/`preferredDid`, which the hub fills from `name` and the DID, and without `legalEntityId`, which carries the declared `memberId`), `legalPerson`, `businessSites`, `gtc`, `ucas`, `declarations`; the hub adds `applicantReference` and placeholder GTC/UCA documents). Returns the membership record incl. its `externalId`. `400` for a dataspace the hub does not serve or a `registration` it refuses. |
| `GET /api/members/{externalId}` | The correlated view. For a member with a deployed profile, resolves the stored profile id and reads its current state from the Tenant Manager. |
| `GET /api/members?dataspace=&memberId=` / `?did=[&dataspace=]` | Rediscovery: the memberships under a member id (of one dataspace) or a DID. |
| `POST /api/callbacks/{dataspace}/registration-status` | The status-callback endpoint registered with each dataspace's onboarding API, in that API's own wire format (`404` for a dataspace whose status is polled, e.g. Decade-X). OAuth2-protected: the caller presents a client-credentials bearer from the OSP IdP, obtained with the client this app registers alongside its callback URL. Not meant for humans. |

States: `PROVISIONING → PROVISIONED → SUBMITTED → CREDENTIALS_OFFERED`, with `REJECTED`/`FAILED`
as terminal off-ramps. A member that brought its own DID starts at `SUBMITTED` and its `POST`
returns from the completed registration; a member hosted here returns in `PROVISIONING` and is
carried the rest of the way on a background worker. `CONFIRMED` and `REGISTERING` are legacy
states, no longer produced but still readable and still able to advance, so rows an older hub
left behind heal on a redelivered callback.

`CREDENTIALS_OFFERED` is the terminal success of EVERY member: its registration was confirmed,
which (for Catena-X) means the onboarding API registered the credential holder AND had the
IssuerService offer the membership credentials, which the member's own wallet then requests over
DCP. A Decade-X approval means the same: the TSP registered the holder and had the issuer offer
the `DecadeXMembershipCredential` (claims: the DID and the Decade-X-ID). Having no operator, the
VE's TSP approves requests automatically: those of participants hosted here, and for now those of
external participants too (`dx-onboarding.review` in dx-onboarding-api).

Whether a member's resources are provisioned here follows from the `did`: **supply one** and the
member is taken to run elsewhere (nothing is deployed, and `PROVISIONING`/`PROVISIONED` are
skipped); **omit it** and the hub mints one under `participant.did.template` and deploys the
member's EDC resources before registering it. A member hosted here needs its member id on ingress
either way, because provisioning (the certo activity) needs it before any registration could
assign one. Catena-X requires it for every member, as its status callback does not carry an
assigned one back. An external Decade-X member may leave it out: the TSP then assigns its
Decade-X-ID on approval, and the hub records it with the confirmation.

`POST /api/members` refuses, with `409`, a member id a live membership of the same dataspace
already holds, and a DID a live membership of the same dataspace holds — for a member hosted
HERE, a DID held in ANY dataspace, since its DID is a deployed participant profile. All before
anything is deployed, since a registration the onboarding API declines does NOT undo a deployment
that already happened. Dead attempts (`REJECTED`, `FAILED`) release their identities.

> **Known gap:** a registration declined or failed AFTER the deployment leaves the member's EDC
> resources behind; nothing disposes of them (offboarding is not a flow here yet). The duplicate
> check above is what keeps the common case from getting that far.

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

- `dataspaces.<id>.*` — one entry per dataspace: `enabled`, `display-name`, the onboarding API
  (`onboarding.url`, the OSP OAuth2 client under `onboarding.auth` — seeded in the OSP IdP by the
  umbrella chart — and, for an API with status callbacks, the `onboarding.callback` block this app registers: its URL, ending in
  `/api/callbacks/<id>/registration-status`, plus the token-url/client-id/client-secret the
  onboarding API authenticates the callbacks with, validated via
  `spring.security.oauth2.resourceserver.jwt.*`), the connector's `dataspace-profiles`, and the
  `member-id-claim` the data plane stamps into flow tokens (Catena-X: `BpnCredential`'s `bpn`).
  A dataspace also needs its `DataspaceOnboarding` bean (`DataspacesConfig`).
- `tenant-manager.*` — base URL and the jwtlet mapping (`token-resource`) the workload token is
  exchanged under; the chart's jwtlet-seed job registers it with the
  `tenant-manager-api:read/write` scopes.
- `participant.*` — the DID template and the `cfm.dataplane`/CCM transfer-type mappings sent with
  the participant profile. The `cfm.issuer` VPA properties are always sent (the certo activity
  reads the member id from them, under `bpn`); their content is the dataspace's.

The image is published by `.github/workflows/publish.yml` to
`ghcr.io/metaform/cx-ve/membership-hub`.
