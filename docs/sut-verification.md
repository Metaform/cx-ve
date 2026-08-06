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
   data-plane profile (HttpData-PULL incl. EDR token refresh). Anything else crossing the
   boundary is a harness artifact, not part of the surface — a candidate that needs more than
   DSP + DCP to interoperate fails by construction.
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
| Network reachability + DNS | ve1 must resolve and reach the SUT's endpoints; the kind-specific routes/CoreDNS forwarding of connect-ves.sh are the lab instantiation of this |

Conversely the harness publishes ve1's participant DID, issuer DID and gateway-independent
DSP/DCP endpoints to the SUT.

## Checkpoints and obligations

Each scenario states: what the SUT must have done beforehand (state obligations — *how* is
its business), what ve1 does, and which wire exchanges occur (numbers reference the tables in
[cross-ve-communication.md](cross-ve-communication.md)).

### Checkpoint 0 — discovery & identity

**SUT obligations (state):**
- Participant DID document served and resolvable from ve1 (#1), advertising `ProtocolEndpoint`
  and `CredentialService`.
- Issuer DID document resolvable from ve1 (#3), if the SUT brings its own issuer.

**Verified by:** ve1 resolving both DID documents. No DSP traffic yet.

### Checkpoint 1 — credentials & trust

**SUT obligations (state):**
- The SUT participant *holds* the three Catena-X credentials — `MembershipCredential`
  (`memberOf == "Catena-X"`), `BpnCredential` (matching its declared BPN) and
  `DataExchangeGovernanceCredential` (`contractVersion == "1.0.0"`) — issued by an issuer ve1
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
- An agreed-upon asset exists with an `HttpData-PULL` distribution and a backing data source.
- Access + contract policy gate the asset on the three CX credential constraints
  (`Membership`, `FrameworkAgreement == DataExchangeGovernance:1.0.0`,
  `BusinessPartnerNumber == <ve1 participant's BPN>`).
- A contract definition exposes the asset in the catalog for authorized consumers.
- Its data plane can mint/serve EDR tokens and (if advertised) support token refresh.

**ve1 actions & observed ping-pong** (ve1 = consumer, direction of #7–#14 reversed):
1. Catalog request → offer for the asset must be present, with the constrained policy (#8;
   negative probe: a consumer without the credentials must NOT see the offer).
2. Contract negotiation mirroring the offer → SUT must callback agreement (#10), accept the
   verification (#11) and finalize (#12).
3. Transfer request (`HttpData-PULL`) → SUT must send TransferStart with a working EDR (#14).
4. Data pull with the EDR token → payload bytes; renewal via the SUT's refresh endpoint where
   advertised (#15/#16 mirrored).

**Asserted on ve1's side only:** negotiation FINALIZED, transfer STARTED, payload received —
all observable through ve1's management API and the downloaded bytes.

### Checkpoint 3 — SUT as consumer (the SUT fires the requests)

**SUT obligations (actions, not just state):** against ve1's seeded, credential-constrained
offer (what `dsp-tests.sh` seeds today), the SUT must at some point initiate and complete:
1. Catalog request to ve1's DSP endpoint (#8) and locate the offer.
2. Negotiation mirroring the offer policy exactly (#9, #11) through to FINALIZED (#10, #12).
3. An `HttpData-PULL` transfer (#13) to STARTED (#14).
4. The data pull using the EDR from the TransferStartMessage (#16), with refresh against
   ve1's siglet where needed (#15).

**Asserted on ve1's side only:** ve1's management API shows the negotiation reaching agreement
/ FINALIZED and the transfer STARTED for the SUT's participant id; ve1's data source gets hit.
(With the current demo data source — public jsonplaceholder — the pull is not observable on
ve1; making the harness serve the payload itself is the planned improvement so that
consumer-role verification can assert the actual download.)

## What is explicitly out of scope

The SUT's management APIs, onboarding process, wallet/agent internals, auth stack and
deployment shape. Equally, the reference harness's own conveniences — the v5beta management
API, jwtlet/clearglass, the tenant manager, the siglet token-cache API used by
`dsp-tests.sh` to fetch the EDR on the consumer side — are driver tooling for ve2 and vanish
from the picture once ve2 is replaced by a real SUT.

## Where the harness stands today

`dsp-tests.sh` implements Checkpoint 2+3 with ve2 as a compliant pseudo-SUT: ve2's
obligations are fulfilled by the platform's own tooling (`onboard-participant.sh` for
credentials/wallet/data plane, the script's seeding steps for the offer), and the script
drives both sides. Evolving it toward this document means extracting the ve2-side operations
behind a "consumer/provider driver" interface and adding the reversed-role scenario — the
ve1-side halves stay as they are.
