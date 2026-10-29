# cx-ve

A dataspace-agnostic Verification Environment (VE): a system under test is verified for a
**use case** (e.g. CCM — company certificate management) in a **dataspace** (so far DECADE-X,
through a stub onboarding API). Each dataspace brings its own onboarding API, registration
payloads, member-id format (DECADE-X: the DECADE-X-ID), policies and DSP profile; the Membership
Hub talks to all of them, and the Verification UI lets the user pick the dataspace and then the use
case before a run.

Monorepo containing:

| Path | Contents |
|---|---|
| `dx-onboarding-api/` | Spring Boot application (Java 17, Gradle) — the **DECADE-X** onboarding API: the TSP onboarding intake (draft onboarding requests, document uploads, submission, status read-back); in memory; no TSP operator yet, so requests are approved automatically (participants hosted by the VE, and for now external ones too); an approval assigns the participant its DECADE-X-ID, registers it as credential holder and has the platform's issuer offer it the `DX_LegalEntity_VC` for that id and the `DX_Site_VC` for its first business site (a participant the VE hosts gets the id the hub generated for its deployment); self-contained build |
| `membership-hub/` | Spring Boot application (Java 17, Gradle) — the Membership Hub: drives the full member journey in each dataspace it serves (EDC resource provisioning via the CFM Tenant Manager, then registration via the dataspace's onboarding API); self-contained build |
| `verification-ui/` | Spring Boot BFF + Angular dashboard — dataspace and use-case selection, verification runs ([README](verification-ui/README.md)) |
| `compliance-tracker/` | CFM lifecycle agent (Go) consuming lifecycle CloudEvents off NATS — self-contained module |
| `charts/dx-onboarding-api/` | Helm chart for the DECADE-X onboarding API stub |
| `charts/decadex-profile/` | Helm chart seeding DECADE-X: the issuer's credential definitions, the EDC dataspace profile and the Tenant Manager's dataspace profile |
| `charts/membership-hub/` | Helm chart for the Membership Hub application |
| `charts/cx-ve/` | Umbrella chart: the whole VE (platform, DECADE-X profile, onboarding API, Membership Hub, Verification UI, Certo + agent) as one release |
| `scripts/` | Utility and automation scripts |
| `vendor-stack/` | A reference system under test for the VE to verify: the VE's own components in a separate kind cluster, plus the vendor-side scripts ([README](vendor-stack/README.md)) |

## How a member gets onboarded

The member journey is split across the two applications, with the Membership Hub as the entry
point (see [membership-hub/README.md](membership-hub/README.md) for its API and states):

1. `POST /api/members` on the **Membership Hub** names the member's `dataspace` and its
   `memberId` there, if the dataspace takes one (DECADE-X does not: the TSP assigns the
   DECADE-X-ID on approval), plus a dataspace-specific `registration` object. The hub
   checks that object against the dataspace, mints an `externalId`, resolves the member's DID,
   and refuses a DID or member id a live membership already holds (`409`). The steps below are
   DECADE-X's; another dataspace would differ in step 3, its onboarding API.
2. If the VE hosts the member (no `did` supplied), the hub creates a **tenant** and deploys the
   **participant profile** via the CFM Tenant Manager, which runs the VPA provisioning
   orchestration: connector, IdentityHub, Siglet and Certo. Neither credential activity is part
   of the DAG. The `POST` returns immediately, in `PROVISIONING`; the deployment runs on a
   worker. A member that brought its own DID skips this entirely — its resources run elsewhere,
   already deployed by its own operator.
3. Once the participant context exists, the hub submits the registration to the **DECADE-X
   onboarding API** (`dx-onboarding-api`, the TSP onboarding intake) in the
   onboarding-service-provider role (OAuth2 against the VE's OSP IdP): it drafts the onboarding
   request, uploads the placeholder GTC/UCA documents and submits it, then polls its status (the
   intake has no callbacks). An approval assigns the DECADE-X-ID, registers the member as a
   credential **holder with the IssuerService** (with the attestation properties the seeded
   credential definitions map from) and has the IssuerService send it a **DCP credential offer**
   (`DX_LegalEntity_VC`, `DX_Site_VC`), which the member's own IdentityHub requests the
   credentials from.
4. That order is why the deployment comes first: the offer is PUSHED to the credential service
   the member's DID document advertises, so the wallet has to exist by the time the registration
   runs. An approved request therefore means "holder registered and credentials offered", and the
   membership ends at `CREDENTIALS_OFFERED`; the delivery itself shows up on the issuance events.
5. `GET /api/members/{externalId}` on the hub returns the correlated record — the participant
   context id and, once the registration is under way, the registration ids — by reading the
   deployed profile's state from the Tenant Manager.

> **Offboarding does not revoke credentials.** The dispose side of the orchestration used to
> revoke them through the CFM onboarding activity, which left with the credential activities.
> Nothing in the VE triggers offboarding today; whatever does it later has to revoke through the
> IssuerService admin API itself.
>
> **A declined registration leaves a hosted member's resources behind.** Deployment precedes
> registration, and nothing takes the profile back when the onboarding API declines. The hub's
> duplicate check catches the repeat case it can see (that is the `409` above); for anything
> else, the orphaned profile has to be removed through the Tenant Manager by hand.

Participants get their data plane registered at provisioning time: the hub attaches the
configured transfer-type mapping (`participant.ccm.*` / `participant.dataplane.*`) to the
`cfm.dataplane` VPA of the participant profile, and the platform's siglet agent installs it in
Siglet and registers the data-plane instance with the control plane.

## Building

The Gradle builds live in the application directories (each its own build root — the
repository root is not a Gradle project):

```shell
cd dx-onboarding-api         # same commands apply in membership-hub/, verification-ui/
./gradlew build              # compile and run tests
./gradlew bootRun            # run the application locally (port 8080)
./gradlew bootBuildImage     # build an OCI container image via buildpacks
docker build -t <name> .     # alternative image build (layered jar Dockerfile)
```

The Go agent in `compliance-tracker/` is a separate, self-contained module — see
[compliance-tracker/README.md](compliance-tracker/README.md):

```shell
cd compliance-tracker
go build ./...
make test-unit             # handler tests; no Docker needed
make test                  # all tests; the launcher suite starts a NATS testcontainer
docker build -t compliance-tracker .
```

## Deploying

The whole VE deploys as ONE umbrella release. The release name `cx-ve` is load-bearing — the
platform names its infra resources `<release>-nats` / `<release>-vault` / `<release>-postgresql`
and the checked-in values reference those names:

```shell
helm dependency update charts/cx-ve
helm lint charts/dx-onboarding-api charts/decadex-profile charts/membership-hub charts/cx-ve
helm install cx-ve charts/cx-ve -n edc-v --create-namespace
```

The umbrella pulls the Core Platform Distribution and Certo as OCI dependencies and vendors the
local `charts/decadex-profile`, `charts/dx-onboarding-api`, `charts/membership-hub` and
`charts/verification-ui`. All seeding runs as post-install hooks of the single release, in one
ordered hook space: platform seeds (weights 10/20) → DECADE-X profile (140 issuer credential
definitions, 145 Tenant Manager dataspace profile, 150 EDC dataspace profile) →
dx-onboarding-api jwtlet mapping (205) → certo jwtlet mappings (210) → certo
activity/orchestration (220) → membership-hub jwtlet mapping (230) → verification-ui jwtlet
mappings (240).

## Local deployment (kind)

`scripts/install-ve.sh` stands up the complete VE on a [kind](https://kind.sigs.k8s.io)
cluster. Run it from the repository root:

```shell
./scripts/install-ve.sh
```

**Prerequisites:** `docker`, `kind`, `helm`, `kubectl`, and the Traefik chart repository
(`helm repo add traefik https://traefik.github.io/charts`).

The script performs the following steps:

1. **Recreates** the kind cluster `cxve` (override the name with `--cluster <name>`; an existing
   cluster of that name is deleted first).
   The kubeconfig is written to `~/.kube/cxve.config`; the generated kind config maps host ports
   80/443 into the node, so the gateway is reachable on `http://localhost` without a
   port-forward.
2. Installs Traefik (`traefik-values.yaml`) and the Gateway API CRDs.
3. Pre-patches CoreDNS (`setup-did-dns.sh --pre`) so the gateway hostnames resolve in-cluster —
   the release's own seed hooks already dereference them during the install.
4. Builds the DECADE-X onboarding API, Membership Hub, Verification UI and
   Compliance Tracker images from this checkout
   and loads them into the cluster, so the VE always runs the local code; every other image is
   pulled from its registry.
5. Installs the **umbrella release** `cx-ve` into namespace `edc-v` (dependencies resolved via
   `helm dependency update`).
6. Re-runs `setup-did-dns.sh` in discovery mode: rewrites are re-derived from the deployed
   HTTPRoutes and verified end to end (in-cluster DNS + the issuer DID document).

For the edit-build-verify loop on an existing cluster, `scripts/redeploy-ve.sh` rebuilds the
app images, re-vendors the charts and upgrades the release in place.

Once complete, the APIs are reachable through the gateway: the Membership Hub at
`http://cxve.localhost/hub` and the DECADE-X onboarding API at `http://cxve.localhost/dx-onboarding`
(Swagger UIs at `<prefix>/swagger`). Onboard a member through the hub with

```shell
curl -s -X POST http://cxve.localhost/hub/api/members \
  -H "Content-Type: application/json" \
  -d '{
    "dataspace": "decade-x",
    "name": "Acme Corp",
    "shortName": "acme",
    "registration": {
      "legalEntity": {
        "registrationCountry": "DE", "companyType": "LIMITED_LIABILITY_COMPANY",
        "registrationNumbers": [ { "scheme": "vatID", "value": "DE123456789" } ],
        "legalAddress": { "street": "Otto-Hahn-Ring 6", "postalCode": "81739", "locality": "Munich",
                          "countryCode": "DE", "countryName": "Germany" }
      },
      "businessSites": [ { "siteName": "Main Site",
                           "mainAddress": { "street": "Otto-Hahn-Ring 6", "postalCode": "81739",
                                            "locality": "Munich", "countryCode": "DE",
                                            "countryName": "Germany" } } ],
      "legalPerson": { "fullName": "Test Operator", "email": "op@acme.example", "role": "Operator" },
      "gtc": { "versionNumber": "1.0" },
      "ucas": [ { "useCaseId": "product-conformity", "versionNumber": "1.0" } ],
      "declarations": { "informationAccurate": true, "authorisedToAct": true, "evidenceMayBeRequested": true }
    }
  }'
curl -s http://cxve.localhost/hub/api/members/<externalId>   # follow the provisioning
```

or let `scripts/onboard-participant.sh` do exactly that — submit through the hub and poll the
membership until it is `CREDENTIALS_OFFERED`:

```shell
./scripts/onboard-participant.sh --name "My Company GmbH"
```

All configuration is checked in statically in `charts/cx-ve/values.yaml`; a non-default
hostname (`--host`) is applied through a small, documented set of `--set` overrides in the
script.

The cluster is left running when the script finishes; remove it with
`kind delete cluster -n cxve`.

### Verifying the VE end to end

The whole sequence — install, onboarding of the "Verification Participant", and an external
resolution of its DID document (plain HTTP from the host through the gateway, exactly the way
an external dataspace solution resolves it) — runs as one end-to-end test (`--skip-install`
reuses an existing cluster):

```shell
./scripts/e2e.sh
```

> The first iteration of this repo demonstrated **two** federated VEs (cross-cluster routing,
> peer DNS, mutual issuer trust, cross-VE DSP exchange via `connect-ves.sh` / `dsp-tests.sh`).
> That machinery was removed when the repo pivoted to a single Verification Environment that
> external solutions connect to; see git history and
> [docs/cross-ve-communication.md](docs/cross-ve-communication.md) (kept as a reference for
> the request flows that cross the VE boundary — the same flows an external solution
> performs). [docs/sut-verification.md](docs/sut-verification.md) builds on it to define the
> state obligations and request ping-pong for verifying a third-party solution.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
