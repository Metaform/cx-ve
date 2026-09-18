# Verifying a third-party solution against the reference VE

This is the target model of the Verification Environment: a **system under test** (SUT, also
"candidate" or "contestant") — any third-party dataspace implementation — connects to the VE
from outside, and the VE (Core Platform + Catena-X profile) verifies the SUT purely over the
wire. In the retired dual-VE demo of the first iteration, "ve2" played the SUT's role and
"ve1" the VE's; the text below keeps that vocabulary.

Two principles follow:

1. **The only interactions between the clusters are DSP and DCP.** The complete request
   catalogue lives in [cross-ve-communication.md](cross-ve-communication.md): layers 1–2 are
   DCP (did:web resolution + presentation exchange), layers 3–4 are DSP and the agreed
   data-plane profile (the Data Plane Signaling HTTP transfer profile, pull direction, incl. EDR
   token refresh). Anything else crossing the boundary is a harness artifact, not part of the
   surface — a candidate that needs more than DSP + DCP to interoperate fails by construction.
2. **How the SUT reaches a required state is immaterial.** Seeding assets, issuing
   credentials, provisioning wallets, registering data planes — the SUT does this with
   whatever management APIs, consoles or scripts it has. Verification only requires that at
   defined checkpoints the SUT cluster is in a *valid state* so that ve1 can fire requests
   against it (or observe the SUT's requests arriving). The reference harness drives ve2
   through the platform's own APIs today, but none of that tooling is part of the contract.

## SUT registration (inputs the harness needs up front)

Because the harness may not introspect the SUT, the SUT declares:

| Input | Used for |
|---|---|
| Participant DID (resolvable from ve1) | Counterparty identity in DSP requests; DID document must advertise the DSP `ProtocolEndpoint` and DCP `CredentialService` |
| Issuer DID (if the SUT brings its own issuer) | ve1's trust anchor config: `edc.controlplane.trustedIssuers` **plus** the `supportedtypes` entry (see connect-ves.sh — without it presentations fail with "credential types not supported for issuer") |
| BPN of the SUT participant | Pinning the `BusinessPartnerNumber` policy constraint in offers made to the SUT |
| Supported DSP profile | Must include the dataspace profile in use (`cx-neptune`) |
| Network reachability + DNS | ve1 must resolve and reach the SUT's endpoints **from inside its cluster** — see below |

Conversely the harness publishes ve1's participant DID, issuer DID and gateway-independent
DSP/DCP endpoints to the SUT.

### Reachability, in both directions

Every check ve1 makes about a SUT is made by a pod: the issuer resolves the SUT's `did:web` to
deliver credential offers, the control plane dials its `ProtocolEndpoint`. A name that only the
operator's machine resolves therefore fails verification, and fails it as "the SUT is
unreachable" — a finding against the vendor. Make the SUT resolvable from the cluster with:

```bash
./scripts/setup-did-dns.sh --sut sut.vendor.example=192.168.1.50
```

Use an address the pods can route to. For a SUT on the operator's own machine that is the host's
LAN address, never `127.0.0.1`: loopback inside a pod is the pod.

The reverse direction is the `-H` install flag. The default host `cxve.localhost` resolves to
loopback on every machine, so a SUT anywhere but ve1's host cannot reach ve1 at all; install with
a hostname that resolves for both parties (`./scripts/install-ve.sh -H ve1.example.com`), which
carries through to every advertised URL and DID.

What the SUT must reach on ve1 is more than its DIDs and DSP endpoint: every credential ve1's
issuer signs names its **status list** (`http://<host>/statuslist/<id>`), and the SUT's wallet
downloads it before presenting the credential, as does its connector when verifying ve1's
participant. An unreachable status list makes the credential unverifiable, and one unverifiable
credential rejects the whole presentation — both directions of DSP then fail with 401. (Core
platform ≥ 0.0.29 publishes the external URL; earlier versions wrote the in-cluster one.)

**Everything is plain HTTP — a known constraint, not an oversight.** `edc.iam.did.web.use.https`
is pinned false across the runtimes and the gateway terminates HTTP only, so ve1 resolves
`did:web` over `http://` and publishes `http://` endpoints and DIDs. A SUT must therefore serve
its DID document over HTTP and accept ve1's HTTP endpoints — note this contradicts the did:web
method's default of HTTPS, so a SUT that only serves HTTPS cannot be verified today. TLS is an
all-or-nothing change across the runtimes and the gateway, and is deferred.

## Checkpoints and obligations

Each scenario states: what the SUT must have done beforehand (state obligations — *how* is
its business), what ve1 does, and which wire exchanges occur (numbers reference the tables in
[cross-ve-communication.md](cross-ve-communication.md)).

### Checkpoint 0 — discovery & identity

**SUT obligations (state):**
- Participant DID document served and resolvable from ve1 (#1), advertising `ProtocolEndpoint`
  and `CredentialService`.
- Those endpoints must be the ones the SUT actually serves. The DID document is the *only* way
  ve1 can discover where to reach a SUT, so an advertised endpoint that does not answer is a
  conformance failure and is reported as one — ve1 offers no way to override the address by
  hand. Note that the DSP endpoint's path identifies a dataspace profile, and the profile in
  use here is `cx-neptune`: a connector advertising a different binding than it serves (the EDC
  default `http-dsp-profile-2025-1` is the easy mistake) fails this checkpoint.
- Issuer DID document resolvable from ve1 (#3), if the SUT brings its own issuer.

**Verified by:** ve1 resolving both DID documents, and — at checkpoint 2 — the first DSP request
to the advertised `ProtocolEndpoint`. A counterparty answering `404`/`405` there fails the run
immediately, naming the address dialled, rather than being waited out as a slow offer.

### Checkpoint 1 — credentials & trust

**SUT obligations (state):**
- The SUT participant *holds* the three Catena-X credentials — `MembershipCredential`
  (`memberOf == "Catena-X"`), `BpnCredential` (matching its declared BPN) and
  `DataExchangeGovernanceCredential` (`contractVersion == "1.0"`) — issued by an issuer ve1
  trusts. Topology is the SUT's choice: its own (registered) issuer, or credentials issued by
  ve1's issuer service via DCP issuance (the more complete conformance target, not yet part of
  the harness).
- Its CredentialService answers DCP presentation queries (#5) for those credentials under the
  Catena-X scope mapping.
- The SUT trusts ve1's issuer in return, so it can verify ve1's presentations (#6, #4).

**Verified by:** implicitly in every subsequent scenario — presentation exchange happens on
each DSP message. A dedicated probe is possible (fire a catalog request and check the SUT
verifies ve1's VP and vice versa) before asserting anything content-related.

### Checkpoint 2 — SUT as provider (ve1 fires the requests)

**SUT obligations (state):**
- An agreed-upon asset exists with a distribution of format
  `https://w3id.org/dspace-sig/profile/http-pull` — the Data Plane Signaling HTTP
  [transfer profile](https://eclipse-dataplane-signaling.github.io/profiles/HEAD/#transfer-profiles)
  value for pull, which is also the `endpointType` of the DataAddress its data plane hands out —
  and a backing data source. The asset itself carries no address: the data plane owns the
  endpoint.
- Access + contract policy gate the asset on the three CX credential constraints
  (`Membership`, `FrameworkAgreement == DataExchangeGovernance:1.0`,
  `BusinessPartnerNumber == <ve1 participant's BPN>`).
- A contract definition exposes the asset in the catalog for authorized consumers.
- Its data plane can mint/serve EDR tokens and (if advertised) support token refresh.

**ve1 actions & observed ping-pong** (ve1 = consumer, direction of #7–#14 reversed):
1. Catalog request → offer for the asset must be present, with the constrained policy (#8;
   negative probe: a consumer without the credentials must NOT see the offer).
2. Contract negotiation mirroring the offer → SUT must callback agreement (#10), accept the
   verification (#11) and finalize (#12).
3. Transfer request (transfer type `https://w3id.org/dspace-sig/profile/http-pull`) → SUT must send TransferStart with a
   working EDR (#14).
4. Data pull with the EDR token → payload bytes; renewal via the SUT's refresh endpoint where
   advertised (#15/#16 mirrored).

**Asserted on ve1's side only:** negotiation FINALIZED, transfer STARTED, payload received —
all observable through ve1's management API and the downloaded bytes.

### Checkpoint 3 — SUT as consumer (the SUT fires the requests)

**SUT obligations (actions, not just state):** against ve1's seeded, credential-constrained
offer (what `dsp-tests.sh` seeds today), the SUT must at some point initiate and complete:
1. Catalog request to ve1's DSP endpoint (#8) and locate the offer.
2. Negotiation mirroring the offer policy exactly (#9, #11) through to FINALIZED (#10, #12).
3. A transfer of type `https://w3id.org/dspace-sig/profile/http-pull` (#13) to STARTED (#14).
4. The data pull using the EDR from the TransferStartMessage (#16), with refresh against
   ve1's siglet where needed (#15).

**Asserted on ve1's side only:** ve1's management API shows the negotiation reaching agreement
/ FINALIZED and the transfer STARTED for the SUT's participant id; ve1's data source gets hit.
(With the current demo data source — public jsonplaceholder — the pull is not observable on
ve1; making the harness serve the payload itself is the planned improvement so that
consumer-role verification can assert the actual download.)

## What is explicitly out of scope

The SUT's management APIs, onboarding process, wallet/agent internals, auth stack and
deployment shape. Equally, the reference harness's own conveniences — the v5 management
API, jwtlet/clearglass, the tenant manager, the siglet token-cache API used by
`dsp-tests.sh` to fetch the EDR on the consumer side — are driver tooling for ve2 and vanish
from the picture once ve2 is replaced by a real SUT.

## Running a verification against a SUT (Verification UI)

The Verification UI implements this document for the CX-0135 certificate exchange. Entering a
**participant DID** on the run form switches it from onboarding a participant into the VE to
verifying one that already exists elsewhere: nothing is provisioned for that DID, and only the
VE's own half of the exchange is driven from here.

**Declared up front** (run form): the participant DID, and optionally the company name and the
BPN the VE should issue credentials for (otherwise derived).

**What the VE does, in order** — each step waits for the SUT rather than acting on it:

| # | VE | SUT obligation to proceed |
|---|---|---|
| 1 | Resolves the DID document | Served and reachable from the VE, advertising `ProtocolEndpoint` and `CredentialService` (Checkpoint 0) |
| 2 | Registers the DID as a credential holder and has its IssuerService send a DCP CredentialOffer to the advertised `CredentialService` | Accept the offer and request the credentials (Checkpoint 1). The VE waits for `events.issuance.credential.delivered` in its ledger — nothing else proves the SUT holds them |
| 3 | Requests the SUT's catalog as the verification participant, negotiates and starts a `https://w3id.org/dspace-sig/profile/http-pull` transfer | An asset fronting its CCM API, declaring the CX-0135 provider API — `dct:type` `cx-taxo:CCMAPI`, `dct:subject` `cx-taxo:CompanyCertificateManagementProviderApi`, `cx-common:version` (`verification.ccm-api-version`, default `3.0`) — gated on the three CX credential constraints (Checkpoint 2). The VE finds it by those properties, whatever its id; a catalog offering the API twice fails the run, since CX-0135 allows one offer per API and version |
| 4 | Waits for a certificate on the verification participant's inbox | Find the VE's permanent inbox offer — the dataset declaring the CX-0135 consumer API (`cx-taxo:CompanyCertificateManagementConsumerApi`) — consume it and push a certificate over that flow (Checkpoint 3 + CX-0135 Flow B) |
| 5 | Retrieves the certificate over the pull flow and reports the `ACCEPTED` verdict | — |

Two consequences worth stating plainly. The VE cannot compare the retrieved document against an
original, since the SUT authored it; step 5 checks the delivery's internal consistency, and the
exchange having happened under VE-issued credentials is the finding. And the compliance ledger
can only attribute a SUT's **onboarding and credential delivery** to it — the exchange's own
events carry the verification participant's context — so the ledger checklist for an external run
is deliberately short (`verification.external.expected-events`).

The VE issues to its own participants exactly this way too: every member the Membership Hub
onboards — the verification participant included — is offered its credentials over DCP and
requests them with its own wallet. There is no privileged path for a participant that happens to
run inside the VE, so the issuance this document asks of a SUT is the one the VE exercises on
every run.

**A reference SUT** to run this against lives in [vendor-stack/](../vendor-stack/README.md): the
VE's own components in a separate kind cluster, provisioned without credentials of their own, with
scripts for every obligation in the table above.

`dsp-tests.sh` remains the older path: it implements Checkpoint 2+3 with ve2 as a compliant
pseudo-SUT, whose obligations are fulfilled by the platform's own tooling (the Membership Hub's
`POST /hub/api/members` for credentials/wallet/data plane, the script's seeding steps for the
offer), driving both sides.
