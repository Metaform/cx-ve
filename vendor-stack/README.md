# Vendor stack — a reference system under test

A dataspace stack for the Verification Environment (VE) to verify: an **externally hosted**
participant, reachable only over DSP, DCP and CCM, exactly as
[docs/sut-verification.md](../docs/sut-verification.md) expects of a third-party solution.

It is built from the VE's own components — the Core Platform Distribution, the Catena-X profile
seeding (plus the DECADE-X profile in a DECADE-X stack), Certo and the Certo CFM agent, at the
versions `charts/cx-ve` pins — and runs in its **own kind cluster**. There is one stack per
dataspace, both deployed with the same scripts: a **Catena-X** stack (the default) and a
**DECADE-X** stack (`-d decade-x` on every script). The two can run side by side next to the VE.
So it proves the VE's side of the wire end to end, not interoperability with a foreign
implementation: that is the next step, with a different vendor's connector and wallet in this
stack's place.

None of the VE's applications (Onboarding APIs, Membership Hub, Verification UI, Compliance
Tracker) run here. What a vendor does with its own tooling is scripted in [`scripts/`](scripts).

## Topology

| | VE | Catena-X vendor stack | DECADE-X vendor stack |
|---|---|---|---|
| kind cluster | `cxve` (`~/.kube/cxve.config`) | `vendor` (`~/.kube/vendor.config`) | `dx-vendor` (`~/.kube/dx-vendor.config`) |
| release | `cx-ve` | `vendor-stack` | `vendor-stack` |
| gateway | `http://cxve.localhost` (port 80) | `http://vendor.localhost:8080` | `http://dx-vendor.localhost:8081` |
| participant DID | `did:web:identity.cxve.localhost:verification-participant` (DECADE-X: `…:verification-participant-dx`) | `did:web:identity.vendor.localhost%3A8080:vendor-participant` | `did:web:identity.dx-vendor.localhost%3A8081:vendor-participant` |
| member id | BPN `BPNLVERIFY000001` / DECADE-X-ID `DX-99999999` | BPN `BPNLVENDOR000001` | DECADE-X-ID `DX-00009001` |
| issuer DID | `did:web:issuer.cxve.localhost:issuer` | `did:web:issuer.vendor.localhost%3A8080:issuer` (issues nothing) | `did:web:issuer.dx-vendor.localhost%3A8081:issuer` (issues nothing) |
| trusts | its own issuer | its own issuer **and the VE's** | its own issuer **and the VE's** |
| DSP profile | `cx-neptune` and `decade-x` | `cx-neptune` | `decade-x` |

**Why the port is in the DIDs.** The VE's cluster holds port 80 on the host, so the stacks'
gateways are on 8080 (Catena-X) and 8081 (DECADE-X) — and the platform advertises the port
(`global.external.port`) in every URL and DID. That makes one address work from every place that
dereferences it: the host (kind port mapping), the stack's own pods (CoreDNS rewrite to the Traefik
Service, exposed on that port) and the VE's pods (CoreDNS `hosts` entry to the stack's node IP,
where Traefik binds that port as hostPort).

**Transfers follow the Data Plane Signaling HTTP transfer profile.** Both certificate-exchange
flows are pull transfers of type `https://w3id.org/dspace-sig/profile/http-pull` — the
[transfer profile](https://eclipse-dataplane-signaling.github.io/profiles/HEAD/#transfer-profiles)
value, which is also the `endpointType` of the DataAddress the data plane hands out. Assets carry
no address of their own: the participant's data-plane mapping points that transfer type at Certo.
(The profile requires HTTPS endpoints; like the VE, this stack runs HTTP only.)

**Why nothing in this stack issues credentials.** The participant is provisioned without CFM's
credential activities (`chart/templates/certo-activity-seed-job.yaml`). Its credentials come from
the VE: onboarding the participant in a verification run registers the DID as a holder with the
VE's issuer, which sends a DCP credential offer, and IdentityHub requests the offered credentials
by itself. A credential from this stack's own issuer would not merely be redundant — the VE does
not trust that issuer, and one untrusted credential makes the VE reject the whole presentation.

## Deploying a vendor stack

The VE serves both dataspaces, so one VE is enough for both stacks. Each block below deploys one
stack and runs one verification against it; [Step-by-step](#step-by-step-verifying-a-vendor-stack)
explains every command. All commands run from the repository root.

### Catena-X

```shell
./scripts/install-ve.sh                                   # the VE, unless it is already running
./vendor-stack/install.sh                                 # cluster vendor, http://vendor.localhost:8080
./vendor-stack/connect.sh
./vendor-stack/scripts/create-participant.sh              # BPN BPNLVENDOR000001 (-m to change)
./vendor-stack/scripts/seed-ccm-offer.sh

# verify it: start a run, then push the certificate once the run has passed "Resolve participant DID"
curl -s -X POST http://cxve.localhost/ui/api/runs -H 'Content-Type: application/json' \
  -d '{"dataspace":"catena-x","useCase":"ccm","name":"Vendor Participant","memberId":"BPNLVENDOR000001",
       "did":"did:web:identity.vendor.localhost%3A8080:vendor-participant"}' | jq -r .id
./vendor-stack/scripts/push-certificate.sh
./vendor-stack/scripts/status.sh --exchange <exchange id>
```

### DECADE-X

```shell
./scripts/install-ve.sh                                   # the VE, unless it is already running
./vendor-stack/install.sh -d decade-x                     # cluster dx-vendor, http://dx-vendor.localhost:8081
./vendor-stack/connect.sh -d decade-x
./vendor-stack/scripts/create-participant.sh -d decade-x  # DECADE-X-ID DX-00009001 (-m to change)
./vendor-stack/scripts/seed-ccm-offer.sh -d decade-x

# verify it: start a run, then push the certificate once the run has passed "Resolve participant DID"
curl -s -X POST http://cxve.localhost/ui/api/runs -H 'Content-Type: application/json' \
  -d '{"dataspace":"decade-x","useCase":"ccm","name":"Vendor Participant","memberId":"DX-00009001",
       "did":"did:web:identity.dx-vendor.localhost%3A8081:vendor-participant"}' | jq -r .id
./vendor-stack/scripts/push-certificate.sh -d decade-x
./vendor-stack/scripts/status.sh -d decade-x --exchange <exchange id>
```

### What differs between the two stacks

| | Catena-X | DECADE-X |
|---|---|---|
| scripts | no flag (`-d catena-x`) | `-d decade-x` on every script |
| cluster, gateway | `vendor`, `http://vendor.localhost:8080` | `dx-vendor`, `http://dx-vendor.localhost:8081` |
| member id | BPN, `-m`/`-b` (default `BPNLVENDOR000001`) | DECADE-X-ID, `-m` (default `DX-00009001`) — declared up front like a BPN; the VE's onboarding API honors it |
| credentials from the VE | `MembershipCredential`, `BpnCredential`, `DataExchangeGovernanceCredential` | `DecadeXMembershipCredential` (claims: the DID, `decadeXId`) |
| DSP profile | `cx-neptune` | `decade-x` — its DCP scope asks for the `DecadeXMembershipCredential`; DECADE-X cannot share `cx-neptune`, whose scopes ask for the Catena-X credentials |
| offer policies | Catena-X constraints (membership, framework agreement, …) | none yet — no DECADE-X policy vocabulary exists |
| VE counterparty | `…:verification-participant`, BPN `BPNLVERIFY000001` | `…:verification-participant-dx`, DECADE-X-ID `DX-99999999` |

A DID document advertises ONE DSP profile per platform. The VE advertises `cx-neptune` for every
participant, so `push-certificate.sh -d decade-x` replaces the profile segment of the VE
verification participant's advertised endpoint with `decade-x`; a DECADE-X stack itself advertises
`decade-x`, which is the endpoint the VE dials.

## Step-by-step: verifying a vendor stack

Each step shows the Catena-X and the DECADE-X command; they differ only by `-d decade-x` and by the
values that follow from it. Times are rough figures for a local machine.

### 0. Prerequisites

- `docker`, `kind`, `helm`, `kubectl`, `curl`, `jq`
- the Traefik chart repository: `helm repo add traefik https://traefik.github.io/charts`
- host ports free: **80** (VE), **8080** (Catena-X stack), **8081** (DECADE-X stack)
- room for full platforms — the VE and one vendor stack use about 9 GB of memory together, and
  running both stacks needs roughly 4 GB more

### 1. Install the VE (≈ 15 min)

```shell
./scripts/install-ve.sh
```

Recreates the `cxve` cluster, builds the VE's applications and installs them with the platform.
Done when the output ends with `DID DNS setup verified for cluster 'cxve'.` — the Verification UI
is then at <http://cxve.localhost/ui>.

Skip this step if the VE is already running on core platform ≥ 0.0.29.

### 2. Install the vendor stack (≈ 5 min)

```shell
./vendor-stack/install.sh                  # Catena-X
./vendor-stack/install.sh -d decade-x      # DECADE-X
```

Recreates the stack's cluster (an existing one is deleted) and installs the stack. A DECADE-X
stack also seeds the `decade-x` DSP profile, trusting the VE's issuer for it. Done when it prints
`Vendor stack (catena-x) is up` or `Vendor stack (decade-x) is up`. Check that its identity carries
the port:

```shell
curl -s http://issuer.vendor.localhost:8080/issuer/did.json | jq -r .id
# did:web:issuer.vendor.localhost%3A8080:issuer
curl -s http://issuer.dx-vendor.localhost:8081/issuer/did.json | jq -r .id
# did:web:issuer.dx-vendor.localhost%3A8081:issuer
```

### 3. Connect the stack to the VE (≈ 2 min)

```shell
./vendor-stack/connect.sh                  # Catena-X
./vendor-stack/connect.sh -d decade-x      # DECADE-X
```

Points each cluster's DNS at the other's gateway, then fetches each side's issuer DID document from
a pod on the other side. Done when it prints `Connected: cxve <-> vendor` or
`Connected: cxve <-> dx-vendor`. Each stack's entries in the VE's DNS are tagged with its cluster
name, so connecting one stack leaves the other's in place.

Re-run this after re-installing either cluster, and after a docker restart.

### 4. Create the vendor participant (≈ 1 min)

```shell
./vendor-stack/scripts/create-participant.sh                # Catena-X
./vendor-stack/scripts/create-participant.sh -d decade-x    # DECADE-X
```

Provisions the participant (control plane, wallet, DID document, data plane, Certo tenant) and
prints what the VE needs to know about it — in a Catena-X stack:

```
Vendor participant provisioned (catena-x).
  DID:                  did:web:identity.vendor.localhost%3A8080:vendor-participant
  BPN:                  BPNLVENDOR000001
  participant context:  b049e6ad9c284e9189ab19c8949f1488
  DID document:         http://identity.vendor.localhost:8080/vendor-participant/did.json
  DSP endpoint:         http://vendor.localhost:8080/api/dsp/b049e6ad…/cx-neptune
```

and in a DECADE-X stack:

```
Vendor participant provisioned (decade-x).
  DID:                  did:web:identity.dx-vendor.localhost%3A8081:vendor-participant
  DECADE-X-ID:          DX-00009001
  participant context:  <participant context id>
  DID document:         http://identity.dx-vendor.localhost:8081/vendor-participant/did.json
  DSP endpoint:         http://dx-vendor.localhost:8081/api/dsp/<participant context id>/decade-x
```

Its wallet is empty at this point — `scripts/status.sh` shows `none — the VE has not delivered any`.

A different name, short name or member id: `-n`, `-s`, `-m` (`-b` is an alias of `-m`). The short
name is the last DID segment and identifies the participant to every other script, so pass the
same `-s` to `seed-ccm-offer.sh`, `push-certificate.sh` and `status.sh` — and enter the matching
DID and member id in step 6.

### 5. Seed the certificate offer

```shell
./vendor-stack/scripts/seed-ccm-offer.sh                # Catena-X
./vendor-stack/scripts/seed-ccm-offer.sh -d decade-x    # DECADE-X
```

Creates the certificate offer the VE's verification participant will negotiate: an asset
declaring the CX-0135 provider API (`dct:type` `cx-taxo:CCMAPI`, `dct:subject`
`cx-taxo:CompanyCertificateManagementProviderApi`, `cx-common:version` `3.0`), its policies and
contract definition. The VE finds the offer by those properties, so the asset id is free — by
default `<short name>-ccm-provider-api`. CX-0135 allows one offer per API and version per business
partner, and a run fails when the catalog offers it twice. Can run any time after step 4.

The policies mirror the VE's own offers in the dataspace. In Catena-X, seeing the offer requires a
Catena-X membership, and a contract requires the framework agreement plus the usage purpose and
usage end definitions. In DECADE-X they are unconstrained, but the `decade-x` profile still
requires the consumer to present its `DecadeXMembershipCredential`.

### 6. Start a verification run on the VE

In the Verification UI (<http://cxve.localhost/ui>), pick the dataspace — **Catena-X** or
**DECADE-X** — and **Company Certificate Management**, then under **Start a verification run**:

| Field | Catena-X | DECADE-X |
|---|---|---|
| Participant DID | `did:web:identity.vendor.localhost%3A8080:vendor-participant` | `did:web:identity.dx-vendor.localhost%3A8081:vendor-participant` |
| BPN / DECADE-X-ID | `BPNLVENDOR000001` | `DX-00009001` |

The member id must be the one from step 4. Name and short name are disabled once a DID is
entered: the participant is named by its own system. Then **Start verification run**. The same
from the command line:

```shell
# Catena-X
curl -s -X POST http://cxve.localhost/ui/api/runs -H 'Content-Type: application/json' \
  -d '{"dataspace":"catena-x","useCase":"ccm","name":"Vendor Participant","memberId":"BPNLVENDOR000001",
       "did":"did:web:identity.vendor.localhost%3A8080:vendor-participant"}' | jq -r .id
# DECADE-X
curl -s -X POST http://cxve.localhost/ui/api/runs -H 'Content-Type: application/json' \
  -d '{"dataspace":"decade-x","useCase":"ccm","name":"Vendor Participant","memberId":"DX-00009001",
       "did":"did:web:identity.dx-vendor.localhost%3A8081:vendor-participant"}' | jq -r .id
```

The run opens with its step list. On a fresh VE, the first step onboards the VE's own verification
participant of that dataspace, which takes a minute or two.

### 7. Push a certificate — once the run has passed "Resolve participant DID"

```shell
./vendor-stack/scripts/push-certificate.sh                # Catena-X
./vendor-stack/scripts/push-certificate.sh -d decade-x    # DECADE-X
```

It needs the VE's verification participant to exist, so start it after the run's first two steps
are done. It then waits by itself: the VE's inbox offer is only visible to a participant holding
the VE-issued credentials, so it keeps logging `not in the catalog yet` until the run has delivered
them. Then it negotiates, opens the push flow and publishes an ISO9001 certificate:

```
Certificate pushed to the verification participant.
  certificate:  97d75e5e-… (ISO9001, holder BPNLVENDOR000001)
  exchange:     56ff34a5-…
```

In DECADE-X, the holder is the DECADE-X-ID (`DX-00009001`).

**Run it once per verification run** — the VE fails a run that finds two pushed certificates
waiting on its verification participant.

### 8. Watch the run finish

Each step and whose move it is:

| Step (UI label) | Who acts | What happens |
|---|---|---|
| Ensure verification participant | VE | its consumer participant exists, with its inbox offer |
| Resolve participant DID | VE | fetches the vendor's DID document from inside its cluster |
| Onboard participant | VE | onboards the DID into the dataspace and registers it as a credential holder |
| Offer credentials | VE | its issuer sends a DCP credential offer to the vendor's wallet |
| Await credential delivery | **vendor** | the wallet requests the credentials; the issuer delivers them |
| Establish pull flow | VE, needs step 5 | finds the provider API offer, negotiates it, starts the transfer |
| Await pushed certificate | **vendor**, step 7 | the certificate arrives on the verification participant |
| Retrieve & verify document | VE | pulls certificate and document over the pull flow |
| Accept certificate | VE | records ACCEPTED and reports it back to the vendor |

The run ends **SUCCEEDED**. The vendor's side of the same exchange, with the id step 7 printed:

```shell
./vendor-stack/scripts/status.sh --exchange <exchange id>
# == credentials
#    MembershipCredential from did:web:issuer.cxve.localhost:issuer (state 500)
#    BpnCredential from did:web:issuer.cxve.localhost:issuer (state 500)
#    DataExchangeGovernanceCredential from did:web:issuer.cxve.localhost:issuer (state 500)
# == certificate exchange 56ff34a5-…
#    fulfillment FULFILLED, acceptance ACCEPTED

./vendor-stack/scripts/status.sh -d decade-x --exchange <exchange id>
# == credentials
#    DecadeXMembershipCredential from did:web:issuer.cxve.localhost:issuer (state 500)
# == certificate exchange …
#    fulfillment FULFILLED, acceptance ACCEPTED
```

### 9. Run it again

Nothing needs reinstalling: repeat **step 6** (the run adopts the existing membership — its
"Onboard participant" step says `adopted` — and the credentials are already there) and **step 7**.

### Tear down

```shell
kind delete cluster -n vendor      # the Catena-X vendor stack
kind delete cluster -n dx-vendor   # the DECADE-X vendor stack
kind delete cluster -n cxve        # the VE
```

## Troubleshooting

The commands below are the Catena-X ones; for a DECADE-X stack, add `-d decade-x`.

| Symptom | Cause | Fix |
|---|---|---|
| `connect.sh` prints `FAIL` for a peer check | a cluster was re-installed or docker restarted, node IPs changed | re-run `connect.sh` |
| run fails at "Resolve participant DID" | the VE cannot resolve the vendor's hostnames | re-run `connect.sh` |
| run hangs at "Establish pull flow" | no dataset declares the CX-0135 provider API — the offer is missing, or was created without the API properties | `scripts/seed-ccm-offer.sh -s <short name>`; the run picks the offer up while it still waits (15 min). The VE's log names what the catalog does offer |
| run fails at "Establish pull flow": `offers … ProviderApi 3.0 2 times` | two assets declare the provider API | delete one — CX-0135 allows one offer per API and version |
| `push-certificate.sh`: `no ProtocolEndpoint in the DID document` | the VE's verification participant does not exist yet | wait for the run's first steps (or **Ensure participant** in the UI), then retry |
| run waits at "Await credential delivery", `status.sh` shows no credentials | the wallet did not answer the credential offer | `scripts/request-credentials.sh` requests them explicitly |
| DECADE-X: a catalog or negotiation request is answered `401`, the control plane logs `Number of requested credentials does not match the number of returned credentials` | the request asked for the Catena-X credentials — it went to a `cx-neptune` endpoint, or the VE predates the `decade-x` profile | re-install the VE, then the stack, and re-run `connect.sh -d decade-x` |
| `push-certificate.sh` logs `catalog request answered HTTP 502 … code=401`; the control planes log `Failed to download status list credential` | a cluster runs core platform < 0.0.29, whose credentials name an in-cluster status list | bump the platform, re-install **both** clusters (credentials keep the URL they were issued with) |
| run fails: `2 exchanges are awaiting acceptance` | `push-certificate.sh` ran twice for one run | decide the extra exchange on the VE's Certo, or re-install the VE |
| provisioning or seeding fails after `helm upgrade` | the Catena-X profile seed is not idempotent | always use `install.sh` (fresh install) |

`scripts/status.sh` is the first stop for anything else: provisioning state, the wallet's
credentials and who issued them.

## Caveats

- **Always install from scratch.** `helm upgrade` on the release duplicates the Catena-X dataspace
  profile (its seed is not idempotent) and breaks participant provisioning — in a DECADE-X stack
  too, which carries the Catena-X profile seeding for provisioning.
- **Ids are per participant.** EDC object ids are unique across all participant contexts of a
  control plane, so the scripts derive every id from the short name — several vendor participants
  can share the stack, each needing its own `-s`.
- **Node IPs are not stable.** `connect.sh` pins each cluster's node address into the other's
  CoreDNS; docker restarts can reassign them.
- **The VE-side DNS entries are managed by `setup-did-dns.sh --sut`**, tagged with the vendor
  cluster's name (`--sut-name`): `connect.sh` replaces only its own stack's entries, so a Catena-X
  and a DECADE-X stack can both be connected.
- **HTTP only**, like the VE (see docs/sut-verification.md).
- **Core platform ≥ 0.0.29 on both sides.** Earlier versions wrote the issuer's in-cluster status
  list URL into every credential; across clusters that resolves to the wrong issuer, and every
  presentation is rejected with 401. Credentials keep the URL they were issued with, so a platform
  bump means reinstalling both clusters.
