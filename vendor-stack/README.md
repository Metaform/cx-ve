# Vendor stack — a reference system under test

A dataspace stack for the Verification Environment (VE) to verify: an **externally hosted**
participant, reachable only over DSP, DCP and CCM, exactly as
[docs/sut-verification.md](../docs/sut-verification.md) expects of a third-party solution.

It is built from the VE's own components — the Core Platform Distribution, the Catena-X profile
seeding, Certo and the Certo CFM agent, at the versions `charts/cx-ve` pins — and runs in its
**own kind cluster**. So it proves the VE's side of the wire end to end, not interoperability with
a foreign implementation: that is the next step, with a different vendor's connector and wallet in
this stack's place.

None of the VE's applications (Onboarding API, Membership Hub, Verification UI, Compliance
Tracker) run here. What a vendor does with its own tooling is scripted in [`scripts/`](scripts).

## Topology

| | VE | Vendor stack |
|---|---|---|
| kind cluster | `cxve` (`~/.kube/cxve.config`) | `vendor` (`~/.kube/vendor.config`) |
| release | `cx-ve` | `vendor-stack` |
| gateway | `http://cxve.localhost` (port 80) | `http://vendor.localhost:8080` |
| participant DID | `did:web:identity.cxve.localhost:verification-participant` | `did:web:identity.vendor.localhost%3A8080:vendor-participant` |
| issuer DID | `did:web:issuer.cxve.localhost:issuer` | `did:web:issuer.vendor.localhost%3A8080:issuer` (issues nothing) |
| trusts | its own issuer | its own issuer **and the VE's** |

**Why the port is in the DIDs.** The VE's cluster holds port 80 on the host, so this stack's
gateway is on 8080 — and the platform advertises it (`global.external.port`) in every URL and DID.
That makes one address work from every place that dereferences it: the host (kind port mapping),
this cluster's pods (CoreDNS rewrite to the Traefik Service, exposed on 8080) and the VE's pods
(CoreDNS `hosts` entry to this cluster's node IP, where Traefik binds hostPort 8080).

**Transfers follow the Data Plane Signaling HTTP transfer profile.** Both certificate-exchange
flows are pull transfers of type `https://w3id.org/dspace-sig/profile/http-pull` — the
[transfer profile](https://eclipse-dataplane-signaling.github.io/profiles/HEAD/#transfer-profiles)
value, which is also the `endpointType` of the DataAddress the data plane hands out. Assets carry
no address of their own: the participant's data-plane mapping points that transfer type at Certo.
(The profile requires HTTPS endpoints; like the VE, this stack runs HTTP only.)

**Why nothing in this stack issues credentials.** The participant is provisioned without CFM's
credential activities (`chart/templates/certo-activity-seed-job.yaml`). Its credentials come from
the VE: the verification run registers the DID as a holder and sends a DCP credential offer, and
IdentityHub requests the offered credentials by itself. A credential from this stack's own issuer
would not merely be redundant — the VE does not trust that issuer, and one untrusted credential
makes the VE reject the whole presentation.

## Step-by-step: verifying the vendor stack

All commands run from the repository root. Times are rough figures for a local machine.

### 0. Prerequisites

- `docker`, `kind`, `helm`, `kubectl`, `curl`, `jq`
- the Traefik chart repository: `helm repo add traefik https://traefik.github.io/charts`
- host ports **80** (VE) and **8080** (vendor stack) free
- room for two full platforms — the two clusters use about 9 GB of memory together

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
./vendor-stack/install.sh
```

Recreates the `vendor` cluster (an existing one is deleted) and installs the stack. Done when it
prints `Vendor stack is up`. Check that its identity carries the port:

```shell
curl -s http://issuer.vendor.localhost:8080/issuer/did.json | jq -r .id
# did:web:issuer.vendor.localhost%3A8080:issuer
```

### 3. Connect the two clusters (≈ 2 min)

```shell
./vendor-stack/connect.sh
```

Points each cluster's DNS at the other's gateway, then fetches each side's issuer DID document from
a pod on the other side. Done when it prints `Connected: cxve <-> vendor`.

Re-run this after re-installing either cluster, and after a docker restart.

### 4. Create the vendor participant (≈ 1 min)

```shell
./vendor-stack/scripts/create-participant.sh
```

Provisions the participant (control plane, wallet, DID document, data plane, Certo tenant) and
prints what the VE needs to know about it:

```
Vendor participant provisioned.
  DID:                  did:web:identity.vendor.localhost%3A8080:vendor-participant
  BPN:                  BPNLVENDOR000001
  participant context:  b049e6ad9c284e9189ab19c8949f1488
  DID document:         http://identity.vendor.localhost:8080/vendor-participant/did.json
  DSP endpoint:         http://vendor.localhost:8080/api/dsp/b049e6ad…/cx-neptune
```

Its wallet is empty at this point — `scripts/status.sh` shows `none — the VE has not delivered any`.

A different name, short name or BPN: `-n`, `-s`, `-b`. The short name is the last DID segment and
identifies the participant to every other script, so pass the same `-s` to `seed-ccm-offer.sh`,
`push-certificate.sh` and `status.sh` — and enter the matching DID and BPN in step 6.

### 5. Seed the certificate offer

```shell
./vendor-stack/scripts/seed-ccm-offer.sh
```

Creates the certificate offer the VE's verification participant will negotiate: an asset
declaring the CX-0135 provider API (`dct:type` `cx-taxo:CCMAPI`, `dct:subject`
`cx-taxo:CompanyCertificateManagementProviderApi`, `cx-common:version` `3.0`), its policies and
contract definition. The VE finds the offer by those properties, so the asset id is free — by
default `<short name>-ccm-provider-api`. CX-0135 allows one offer per API and version per business
partner, and a run fails when the catalog offers it twice. Can run any time after step 4.

### 6. Start a verification run on the VE

In the Verification UI (<http://cxve.localhost/ui>), under **Start a verification run**:

| Field | Value |
|---|---|
| Participant DID | `did:web:identity.vendor.localhost%3A8080:vendor-participant` |
| Participant name | `Vendor Participant` (any) |
| Derive BPN from short name | **unchecked** |
| BPN | `BPNLVENDOR000001` — must be the BPN from step 4 |

then **Start verification run**. The same from the command line:

```shell
curl -s -X POST http://cxve.localhost/ui/api/runs -H 'Content-Type: application/json' \
  -d '{"name":"Vendor Participant","bpn":"BPNLVENDOR000001","did":"did:web:identity.vendor.localhost%3A8080:vendor-participant"}' \
  | jq -r .id
```

The run opens with its step list. On a fresh VE, the first step onboards the VE's own verification
participant, which takes a minute or two.

### 7. Push a certificate — once the run has passed "Resolve participant DID"

```shell
./vendor-stack/scripts/push-certificate.sh
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

**Run it once per verification run** — the VE fails a run that finds two pushed certificates
waiting on its verification participant.

### 8. Watch the run finish

Each step and whose move it is:

| Step (UI label) | Who acts | What happens |
|---|---|---|
| Ensure verification participant | VE | its consumer participant exists, with its inbox offer |
| Resolve participant DID | VE | fetches the vendor's DID document from inside its cluster |
| Onboard participant | VE | registers the DID as a credential holder |
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
```

### 9. Run it again

Nothing needs reinstalling: repeat **step 6** (the run adopts the existing membership — its
"Onboard participant" step says `adopted` — and the credentials are already there) and **step 7**.

### Tear down

```shell
kind delete cluster -n vendor   # the vendor stack
kind delete cluster -n cxve     # the VE
```

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `connect.sh` prints `FAIL` for a peer check | a cluster was re-installed or docker restarted, node IPs changed | re-run `connect.sh` |
| run fails at "Resolve participant DID" | the VE cannot resolve the vendor's hostnames | re-run `connect.sh` |
| run hangs at "Establish pull flow" | no dataset declares the CX-0135 provider API — the offer is missing, or was created without the API properties | `scripts/seed-ccm-offer.sh -s <short name>`; the run picks the offer up while it still waits (15 min). The VE's log names what the catalog does offer |
| run fails at "Establish pull flow": `offers … ProviderApi 3.0 2 times` | two assets declare the provider API | delete one — CX-0135 allows one offer per API and version |
| `push-certificate.sh`: `no ProtocolEndpoint in the DID document` | the VE's verification participant does not exist yet | wait for the run's first steps (or **Ensure participant** in the UI), then retry |
| run waits at "Await credential delivery", `status.sh` shows no credentials | the wallet did not answer the credential offer | `scripts/request-credentials.sh` requests them explicitly |
| `push-certificate.sh` logs `catalog request answered HTTP 502 … code=401`; the control planes log `Failed to download status list credential` | a cluster runs core platform < 0.0.29, whose credentials name an in-cluster status list | bump the platform, re-install **both** clusters (credentials keep the URL they were issued with) |
| run fails: `2 exchanges are awaiting acceptance` | `push-certificate.sh` ran twice for one run | decide the extra exchange on the VE's Certo, or re-install the VE |
| provisioning or seeding fails after `helm upgrade` | the Catena-X profile seed is not idempotent | always use `install.sh` (fresh install) |

`scripts/status.sh` is the first stop for anything else: provisioning state, the wallet's
credentials and who issued them.

## Caveats

- **Always install from scratch.** `helm upgrade` on the release duplicates the Catena-X dataspace
  profile (its seed is not idempotent) and breaks participant provisioning.
- **Ids are per participant.** EDC object ids are unique across all participant contexts of a
  control plane, so the scripts derive every id from the short name — several vendor participants
  can share the stack, each needing its own `-s`.
- **Node IPs are not stable.** `connect.sh` pins each cluster's node address into the other's
  CoreDNS; docker restarts can reassign them.
- **The VE-side DNS entries are managed by `setup-did-dns.sh --sut`**, which replaces the whole
  block on each run — entries for other external systems are dropped when `connect.sh` runs.
- **HTTP only**, like the VE (see docs/sut-verification.md).
- **Core platform ≥ 0.0.29 on both sides.** Earlier versions wrote the issuer's in-cluster status
  list URL into every credential; across clusters that resolves to the wrong issuer, and every
  presentation is rejected with 401. Credentials keep the URL they were issued with, so a platform
  bump means reinstalling both clusters.
