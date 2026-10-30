# Membership Hub

Drives a partner's full path into one of the dataspaces this VE hosts (so far DECADE-X, through
its TSP onboarding intake) by combining the two halves the VE deliberately keeps apart:

1. **Provisioning** — for a member this VE hosts, creates a tenant and deploys the participant
   profile via the CFM Tenant Manager, which runs the VPA orchestration (connector, IdentityHub,
   Siglet, Certo — neither credential activity is part of it). The returned participant profile
   id is stored on the record; reading the member resolves it and fetches the profile's current
   state from the Tenant Manager — that is where the `participantContextId` appears and
   deployment errors surface.
2. **Registration** — once the participant context exists, submits the partner to its
   dataspace's onboarding API, acting as an onboarding service provider (OAuth2
   client-credentials against the VE's OSP IdP). For DECADE-X that is the `dx-onboarding-api`, the
   TSP onboarding intake: an approval registers the credential holder with the IssuerService and
   has it offer the member its credentials; the hub polls the request's status, and the approval
   is the membership's terminal success.

Everything dataspace-specific sits behind one `DataspaceOnboarding` implementation per dataspace
(`adapter/out/onboarding/<dataspace>`): the shape of the request's `registration` object, the
onboarding API's endpoints, payloads and authentication, how it reports a registration's outcome
(a status callback in its own wire format — or, for an API without callbacks like DECADE-X's, a
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
| `POST /api/members` | Submit a member: `dataspace`, `name`, `shortName`, `memberId` (the id within the dataspace, for a dataspace that takes one; refused in DECADE-X, where the TSP assigns the DECADE-X-ID on approval), optional `did`, and the dataspace-specific `registration` object (DECADE-X: the TSP's onboarding request data — `legalEntity` (without `legalName`/`preferredDid`, which the hub fills from `name` and the DID, and without `legalEntityId`), `legalPerson`, `businessSites`, `gtc` (`versionNumber`), `ucas` (`useCaseId`, one of the TSP's use cases, and `versionNumber`), `declarations`; the hub adds `applicantReference`, creates the draft, adds placeholder GTC/UCA documents and submits it). Returns the membership record incl. its `externalId`. `400` for a dataspace the hub does not serve or a `registration` it refuses — for an external DECADE-X member also one the TSP refuses, naming its reasons. |
| `GET /api/members/{externalId}` | The correlated view. For a member with a deployed profile, resolves the stored profile id and reads its current state from the Tenant Manager. |
| `GET /api/members?dataspace=&memberId=` / `?did=[&dataspace=]` / `?shortName=[&dataspace=]` | Rediscovery: the memberships under a member id (of one dataspace), a DID, or the short name of a member hosted here (the DID the hub minted for it). |
| `POST /api/callbacks/{dataspace}/registration-status` | The status-callback endpoint registered with each dataspace's onboarding API, in that API's own wire format (`404` for a dataspace whose status is polled, e.g. DECADE-X). OAuth2-protected: the caller presents a client-credentials bearer from the OSP IdP, obtained with the client this app registers alongside its callback URL. Not meant for humans. |

States: `PROVISIONING → PROVISIONED → SUBMITTED → CREDENTIALS_OFFERED`, with `REJECTED`/`FAILED`
as terminal off-ramps. A member that brought its own DID starts at `SUBMITTED` and its `POST`
returns from the completed registration; a member hosted here returns in `PROVISIONING` and is
carried the rest of the way on a background worker. `CONFIRMED` and `REGISTERING` are legacy
states, no longer produced but still readable and still able to advance, so rows an older hub
left behind heal on a redelivered callback.

`CREDENTIALS_OFFERED` is the terminal success of EVERY member: its registration was confirmed,
which means the onboarding API registered the credential holder AND had the IssuerService offer
the member's credentials, which its own wallet then requests over DCP. For DECADE-X: the TSP
registered the holder and had the issuer offer the `DX_LegalEntity_VC` (its partner: the legal entity, under its DECADE-X-ID) and, for a member
that declared a business site, the `DX_Site_VC` (its partner: the first site). Having no operator, the
VE's TSP approves requests automatically: those of participants hosted here, and for now those of
external participants too (`dx-onboarding.review` in dx-onboarding-api).

Whether a member's resources are provisioned here follows from the `did`: **supply one** and the
member is taken to run elsewhere (nothing is deployed, and `PROVISIONING`/`PROVISIONED` are
skipped); **omit it** and the hub mints one under `participant.did.template` and deploys the
member's EDC resources before registering it. A member hosted here needs its member id before it
is deployed, because provisioning (the certo activity) needs it before any registration could
assign one. A dataspace whose onboarding API cannot carry an assigned id back requires it on
ingress for every member. DECADE-X refuses it: the TSP assigns every DECADE-X-ID on approval,
and the hub records it with the confirmation. For a DECADE-X member hosted here, the hub generates
a random DECADE-X-ID no membership holds before deploying it, and sends it along with the
registration (`legalEntity.legalEntityId`, a VE extension the VE's TSP honors for the participants
it hosts), so its credential carries the id it was deployed with.

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
  `member-id-claim` the data plane stamps into flow tokens (DECADE-X: the `dx:DX_LegalEntity_VC`'s
  `partner.partnerID`).
  A dataspace also needs its `DataspaceOnboarding` bean (`DataspacesConfig`).
- `tenant-manager.*` — base URL and the jwtlet mapping (`token-resource`) the workload token is
  exchanged under; the chart's jwtlet-seed job registers it with the
  `tenant-manager-api:read/write` scopes.
- `participant.*` — the DID template and the `cfm.dataplane`/CCM transfer-type mappings sent with
  the participant profile. The `cfm.issuer` VPA properties are always sent (the certo CFM agent
  reads the member id from them, under `dataspaceIdentifier`, and creates the member's certo tenant
  with it); their content is the
  dataspace's.

The image is published by `.github/workflows/publish.yml` to
`ghcr.io/metaform/cx-ve/membership-hub`.
