# DCP Test Plan

## 1. Introduction

### 1.1 Purpose

This plan defines the test cases and test suite to verify interoperability between **Eclipse EDC IdentityHub** (IH)** and the **SAP DIV** (DIV) for DCP v1.0.1 credential issuance in the Catena-X Neptune environment. It provides comprehensive coverage of the credential issuance and presentation behavior required by the [Decentralized Claims Protocol](https://github.com/eclipse-dataspace-dcp/decentralized-claims-protocol) standard.

DCP interoperability in Catena-X has two aspects:

1. **DIV → IH:** the DIV-based issuer issues credentials to an IH Credential Service (the BYOW direction).
2. **IH → DIV:** an IH-based Issuer Service issues credentials to the DIV Credential Service.

Interoperability is achieved through conformance, not pairwise testing. Each implementation is verified independently against the test suite in the Issuer Service and Credential Service roles. The alternative — testing IH and DIV directly against each other — would produce fixes specific to this pair, mask defects behind workarounds, and require separate effort for future BYOW Credential Service implementations. 

The IH and DIV columns in the test tables track each implementation's status per case.

### 1.2 Test basis and approach

Tests are built on the **EDWG TCK Framework** and the existing **DCP TCK tests** (`eclipse-dataspacetck/dcp-tck`).

The test cases in this plan were derived in two steps. First, the normative statements (MUST/SHOULD/MAY) of the [DCP v1.0.1 specification](https://eclipse-dataspace-dcp.github.io/decentralized-claims-protocol/v1.0.1/) (published 2025-11-12) — primarily the Credential Issuance Protocol (§6), the Verifiable Presentation Protocol (§5), and Base Concepts (§4) — were enumerated and turned into implementation-independent test cases. Each case asserts only observable protocol behavior: HTTP status codes, message contents, and effects reachable through protocol endpoints. Implementation internals are out of scope. Second, every case was evaluated against the TCK source (`dcp-testcases` module, `main`, v1.0.3-SNAPSHOT: 88 declared tests, 85 executing, 3 `@Disabled`) to determine whether an existing TCK test exercises it. The **DCP §** and **TCK Test** columns record the result of this trace for each case.

It is noted where the current TCK covers issuance and presentation interop. Missing conformance test cases are cataloged below and will be added to the TCK by Metaform. 

**Exit criterion: all tests must pass.** The existing TCK tests must be executed in full alongside the enhanced tests, and a system under test is conformant only when every executed test passes. There is no partial-pass criterion. MAY-tagged cases are executed per the capability statement (§2.3); once a feature is claimed, its tests are part of the pass requirement.

The plan comprises three categories of tests:

1. **Base tests** — the existing DCP TCK tests (`eclipse-dataspacetck/dcp-tck`), named in the TCK Test column and executed in full per the exit criterion.
2. **Enhanced DCP conformance tests** — new tests for normative requirements (MUST/SHOULD/MAY) the current TCK does not exercise, identified by a blank TCK Test cell with a populated DCP § cell.
3. **Additional expected behavior tests** — new tests for production hardening the specification does not state normatively (the SEC-tagged cases, DCP § = `—`).

Categories 2 and 3 are described in §1.3.
### 1.3 Enhanced and expected behavior tests

**Enhanced DCP conformance tests** — automated tests for normative requirements (MUST/SHOULD/MAY) of DCP v1.0.1 that the current TCK does not exercise. They are identified in §§3–7 by a blank TCK Test cell with a populated DCP § cell. The main clusters:

- **SI token validation edge cases** — `kid` resolution, `capabilityInvocation` enforcement, multi-key handling, unresolvable `sub` DID (TOK-09–11, TOK-13)
- **Rejection flow** — `REJECTED` CredentialMessage handling on the holder, post-acceptance failure reported through the status endpoint, full rejection round trip (CS-STOR-05, IS-REQ-02, RT-03)
- **Batch issuance semantics** — single `CredentialMessage` delivery, whole-request status (IS-REQ-07, RT-04)
- **Request correlation** — exact 201 with a dereferenceable `Location` header on the issuer; client-side correlation from `Location` rather than the response body (IS-REQ-01, CS-REQ-04)
- **Access-token echo** — the `token` claim from the holder's request carried into the delivery token (CS-STOR-14, IS-DELIV-03)
- **Issuer metadata completeness and stability** — all `CredentialObject` properties populated with well-formed values; stable ids (IS-META-02/03)
- **`vc20-bssl/jwt` profile** — round trips with VC DM 2.0 / enveloped JOSE / Bitstring Status List credentials, and credential homogeneity (CS-STOR-02, RT-01, PROF-01); the current TCK generates `vc11-sl2021/jwt` only
- **Revocation publication** — issuer publishes and flips the Bitstring Status List; externally verifiable (RT-05)
- **Issuer-initiated offers and issuance policy** — offer emission with a harness trigger, `issuancePolicy` VP enforcement (IS-OFF-01/02, IS-REQ-08)
- **Credential Service client behavior** — the CS as a client of the issuer: message formation, endpoint discovery, correlation, failure handling (CS-REQ suite; entirely untested today)
- **Presentation-side gaps** — the `vc.id` scope alias, status-based credential filtering, VP proof properties (CS-PRES-11–13)
- **Delivered-credential verification** — the delivered credential passes full verifier-side validation with no manual fixes (IS-DELIV-04)

**Additional expected behavior tests** — behavior a production implementation needs that the spec does not state normatively (SEC tag, DCP § = `—`): trusted-issuer and delivery-origin checks on credential receipt, cryptographic proof verification and holder binding before storage, idempotency (re-delivery, duplicate requests, duplicate offers), and non-leaking error responses. These protect the issuance path against a rogue or misbehaving counterpart and are required for a credible BYOW story even though no TCK test can be derived from spec text.

### 1.4 Execution

The TCK framework is designed to run as part of a **CI pipeline** (Docker image `eclipsedataspacetck/dsp-tck-runtime`, Gradle runner). All tests — existing TCK plus the enhanced tests — run automated. Test reporting is provided by the TCK platform, keyed to the test identifiers used in this plan. For each run, the TCK harness plays the counterpart role: mock Issuer Service when the SUT is a Credential Service (IH or DIV), mock Credential Service when the SUT is an Issuer Service. Cross-implementation runs (§7) then pair the real implementations in both directions.

### 1.5 Document conventions

- **DCP §** — the spec section(s) containing the normative statement(s) the test traces to. Only sections with actual MUST/SHOULD/MAY text binding the tested behavior are listed. `—` means no normative statement exists; the section numbering follows the rendered v1.0.1 spec (§4 Base Concepts, §5 Verifiable Presentation Protocol, §6 Credential Issuance Protocol, Appendix A DCP Profiles), confirmed against the section numbers the TCK itself uses in its `@DisplayName`s.
- **TCK Test** — the TCK test method name (from the `dcp-testcases` source). Blank means no TCK test exercises the case: it is one of the enhanced tests to be added per §1.3.
- **Tag rule** — the TCK tests only normative requirements (all its tests are `@MandatoryTest`), so cases tagged SEC — and untested SHOULD/MAY behaviors — have no TCK test by construction. Notes cite "Tag rule" for these.
- **IH** — status of eclipse-edc IdentityHub/IssuerService for the full case as specified, verified against the IdentityHub/IssuerService codebase and test suite on `main` (as of 2026-08-21). Existing TCK tests are assumed to pass.
- **DIV** — status of the SAP DIV Credential Service; to be filled in during verification.
- **Notes** — partial-coverage caveats and explanation where a status needs it.

**Legend**

| Tag | Meaning |
|---|---|
| `MUST` | Normative requirement of DCP v1.0.1 (RFC 2119) |
| `SHOULD` | Recommended by the specification |
| `MAY` | Optional feature; test applies only if the implementation claims support |
| `SEC` | Security hardening expected of a production implementation; no normative statement in the spec (DCP § is `—`, and no TCK test exists by the tag rule) |

| Column value | Meaning |
|---|---|
| **DCP §** section number(s) | Location of the normative statement(s) the test traces to |
| **DCP §** `—` | No normative statement exists for the behavior |
| **TCK Test** method name | The TCK test exercising the case (see Notes for partial-coverage caveats) |
| **TCK Test** blank | No TCK test — to be added as an enhanced test (§1.3) |
| **IH** ✅ | IdentityHub passes (TCK tests assumed passing, or proven by IH's own test suite) |
| **IH** ❌ | IdentityHub fails the case as specified (verified against `main`) |
| **IH** ⚠️ | Unknown — behavior untested/unverified |
| **IH** `—` | Not applicable (optional feature IH does not claim) |
| **DIV** blank | Not yet assessed |

Where a test expects `4xx`, the exact code is implementation-specific; the assertion is that the request is rejected with a client-error code and produces no side effects.

---

## 2. Requirements

This section details what a system under test must provide to intergate with the test suite. 

### 2.1 System under test

Each SUT, in each role it is tested in, must provide:

- **DID infrastructure.** A resolvable `did:web` DID document per participant. The issuer's document must expose a `service` entry of type `IssuerService`; the Credential Service's document must expose one of type `CredentialService`. Verification methods used for SI tokens must carry the `capabilityInvocation` relationship. Publication is verified by `is_6_2_endpointDiscovery` (issuer side, IS-DISC-01) and `cs_05_02_endpointDiscovery` (Credential Service side, CS-PRES-01).
- **Token issuance.** The SUT must be able to mint its own Self-Issued ID Tokens for outbound calls (typically via an STS). The harness mints tokens for the simulated counterpart itself.
- **Reachability.** All protocol endpoints reachable from the harness. Production deployments must serve HTTPS exclusively (tested by BASE-02; the TCK itself runs over HTTP by design).
- **Operational Storage API** (Credential Service role). The harness loads test credentials by sending `CredentialMessage`s; a Credential Service whose Storage API is not functional cannot be tested at all.
- **Configurable credential definitions** (Issuer Service role). At least one credential type configured end to end per claimed profile: definition, schema, and attestation/claims source.
- **Repeatable state.** CI execution requires that runs be repeatable: the SUT must support state reset between runs or tolerate idempotent re-execution of fixtures.

### 2.2 Harness triggers

The client-behavior suites (CS-REQ, IS-OFF) require the harness to cause the SUT to act as a protocol client. Each SUT must therefore expose an automatable trigger interface:

- **Credential Service role:** an API call that initiates a credential request toward a specified issuer DID for specified credential ids.
- **Issuer Service role:** an API call that emits a credential offer to a specified holder DID.

The trigger interface is outside DCP's scope and may be implementation-specific (IH provides these via its Identity and Admin APIs), but it must be callable from the CI pipeline without manual steps. **Open item:** whether DIV exposes automatable triggers must be confirmed before the client-behavior tests are scheduled. Without triggers, DIV's client behavior can only be observed indirectly in the cross-implementation runs (§7), which weakens negative-path coverage for those suites.

### 2.3 Capability statement

Before test execution, each SUT must declare:

- **Supported DCP profiles.** `vc20-bssl/jwt` is required for Neptune; `vc11-sl2021/jwt` is additionally exercised where claimed. Credentials within one presentation must be homogeneous per profile (PROF-01).
- **Supported optional features** — pre-authorized code flow, key rotation, re-issuance offers. MAY-tagged cases are executed only for claimed features; a claimed feature that fails its cases fails conformance. Revocation via Bitstring Status List is not optional (MUST, RT-05).

### 2.4 Test data

- One fixture credential type per claimed profile for TCK conformance runs; generic types suffice.
- For Neptune verification runs, fixtures must additionally include the Catena-X credential types used in onboarding and CCM (membership, BPN, and certificate credentials) with their schemas and claim mappings.
- Issuer-side status list infrastructure: a publicly resolvable status list credential endpoint, since revocation cases (RT-05, CS-PRES-12) dereference it externally.

---

## 3. Suite TOK — Self-Issued ID Token validation (shared)

Per DCP §4.3/§4.3.3, every authenticated endpoint must validate inbound SI tokens the same way. Run this suite against **each** authenticated endpoint of the SUT (CS: Storage API, Offer API, Presentation Query API; IS: Credential Request API, Status API).

The TCK repeats its token negatives per endpoint with a consistent variant suffix on these test families: `cs_06_05_01_credentialMessage_*` (Storage API), `cs_06_06_01_credentialOfferMessage_*` (Offer API), `is_6_4_x_credentialRequest_*` (Request API), `is_6_8_x_credentialStatusRequest_*` (Status API), and `cs_04_03_03_*` (Presentation Query API, see CS-PRES-02). The TCK Test column below gives the variant suffix; the three blank variants are absent from all families.

Note on the Bearer-header requirement (TOK-02/03): within DCP §6 the MUST is stated explicitly only for the Request API (§6.4) and Status API (§6.8); for the Storage and Offer APIs, authentication traces to §4.3/§4.3.3 plus §6.5's SHOULD-4xx-on-unauthorized. Worth tightening in the spec.

| ID | Stimulus | Expected | Tag | DCP § | TCK Test | IH | DIV | Notes |
|---|---|---|---|---|---|---|---|---|
| TOK-01 | Valid token (`iss`=`sub`=sender DID, `aud`=receiver DID, valid `exp`/`iat`/`jti`, signed with a `capabilityInvocation` key from the sender's DID document) | Request proceeds | MUST | 4.3, 4.3.3 | happy-path tests per endpoint (`cs_06_05_01_credentialMessage`, `cs_06_06_01_credentialOfferMessage`, `is_6_4_1_credentialRequest`, `is_6_8_1_credentialStatusRequest`) | ✅ | | |
| TOK-02 | No `Authorization` header | 401 | MUST | 5.3.1, 6.4, 6.8 | `*_noAuthHeader` | ✅ | | |
| TOK-03 | Header without `Bearer` prefix | 401 | MUST | 5.3.1, 6.4, 6.8 | `*_missingBearerPrefix` / `*_noBearerPrefix` | ✅ | | |
| TOK-04 | `iss` ≠ `sub` | 401 | MUST | 4.3, 4.3.3 | `*_issNotEqualToSub` / `*_issNotEqualSub` | ✅ | | |
| TOK-05 | `aud` ≠ receiver's DID | 401 | MUST | 4.3, 4.3.3 | `*_incorrectAudience` / `*_invalidAud` | ✅ | | |
| TOK-06 | `exp` in the past (beyond leeway) | 401 | MUST | 4.3, 4.3.3 | `*_tokenExpired` | ✅ | | TCK also tests `iat` in the future (`*_iatInFuture`), an extra variant not in this suite |
| TOK-07 | `nbf` in the future (beyond leeway) | 401 | MUST | 4.3.3 | `*_nbfViolated` | ✅ | | |
| TOK-08 | Signature by a key not present in the `sub` DID document | 401 | MUST | 4.3.3 | `*_tokenSignedWithWrongKey` | ✅ | | |
| TOK-09 | `kid` referencing a nonexistent verification method | 401 | MUST | 4.3.3 | | ⚠️ | | |
| TOK-10 | Signing key lacks the `capabilityInvocation` relationship (e.g. listed only under `authentication`) | 401 — signature validity alone is insufficient | MUST | 4.3.3 | | ⚠️ | | |
| TOK-11 | No `kid` header and the DID document contains more than one verification method | 401 | MUST | 4.3.3 | | ⚠️ | | IH partially covers missing-`kid` on some endpoints, but the multi-key rule is unverified |
| TOK-12 | Replay of a previously used `jti` | 401 | MUST | 4.3, 4.3.3 | `*_jtiAlreadyUsed` / `*_jtiUsedTwice` | ✅ | | IH requires `edc.iam.accesstoken.jti.validation=true` |
| TOK-13 | `sub` DID not resolvable | 401 | MUST | 4.3.3 | | ⚠️ | | No TCK variant exists — verified against source. IH's own tests cover an unresolvable issuer DID on the Storage API; other endpoints unverified |

---

## 4. Credential Service (holder) under test

### 4.1 Storage API — `POST <CredentialService>/credentials`

| ID | Stimulus | Expected | Tag | DCP § | TCK Test | IH | DIV | Notes |
|---|---|---|---|---|---|---|---|---|
| CS-STOR-01 | Valid `CredentialMessage` (`status=ISSUED`, one credential container correlating to a pending request via `holderPid`) | 2xx; credential subsequently held by the CS (observable via presentation or an equivalent query) | MUST | 6.5, 6.5.1, 6.5.2 | `cs_06_05_01_credentialMessage` | ✅ | | TCK asserts 2xx only; subsequent holding/usability not asserted — supplemental assertion still needed |
| CS-STOR-02 | Same, for every DCP profile the CS claims to support | 2xx, credential usable | MUST | A.2 | `cs_06_05_01_credentialMessage` (vc11 only) | ✅ | | `vc20-bssl/jwt` never generated by the TCK; IH's own tests cover `vc20-bssl/jwt` storage |
| CS-STOR-03 | Message missing required fields (`@context`, `type`, `issuerPid`, `holderPid`, `status`) | 400 | MUST | 6.5.1 | `cs_06_05_01_credentialMessage_invalidBody` | ✅ | | TCK variant removes `holderPid` only; IH's own tests cover the remaining field omissions |
| CS-STOR-04 | `status` outside `ISSUED`/`REJECTED` | 400 | MUST | 6.5.1 | `cs_06_05_01_credentialMessage_invalidStatus` | ✅ | | |
| CS-STOR-05 | `status=REJECTED` (with `rejectionReason`) | 2xx; nothing stored; the request is subsequently reported as rejected/failed by the CS | MUST | 6.5.1 | | ❌ | | |
| CS-STOR-06 | `status=ISSUED` with empty `credentials` array | Accepted as a no-op (2xx, nothing stored, pending request unaffected) | SEC | — | | ❌ | | Tag rule; `credentials` is OPTIONAL in §6.5.1, empty-array semantics unstated |
| CS-STOR-07 | `holderPid` that matches no pending request | 4xx, nothing stored | MUST | 6.5, 6.5.1 | | ✅ | | No TCK test — verified against source. IH's own tests cover this |
| CS-STOR-08 | Credential type or format that was not requested under this `holderPid` | 4xx, nothing stored | SEC | — | | ✅ | | Tag rule; IH's own tests cover both variants |
| CS-STOR-09 | Delivery by a *different* DID than the issuer the request was addressed to (token itself valid) | Rejected, nothing stored | SEC | — | | ❌ | | Tag rule |
| CS-STOR-10 | Delivery by a DID that is not a trusted issuer of the CS | Rejected, nothing stored | SEC | — | | ❌ | | Tag rule |
| CS-STOR-11 | Credential payload whose proof/signature does not verify against a key from the issuer's DID document | Rejected, nothing stored | SEC | — | | ❌ | | Tag rule |
| CS-STOR-12 | Credential whose `credentialSubject.id` is not the holder's DID | Rejected, nothing stored | SEC | — | | ❌ | | Tag rule; holder binding is normative only verifier-side (§5.4.3, dataspace-conditional) |
| CS-STOR-13 | Exact re-delivery of an already accepted `CredentialMessage` | No-op: 2xx, no duplicate credential | SEC | — | | ⚠️ | | Tag rule; IH behavior untested |
| CS-STOR-14 | The CS issued an access token in its original request's SI token (`token` claim), delivery arrives without it | 4xx (spec: if present, the access token MUST be used by the issuer) | MUST | 6.1, 6.4 | | ❌ | | |
| CS-STOR-15 | Suite TOK | — | MUST | 4.3, 4.3.3 | see §3 | see §3 | | |

### 4.2 Credential Offer API — `POST <CredentialService>/offers`

| ID | Stimulus | Expected | Tag | DCP § | TCK Test | IH | DIV | Notes |
|---|---|---|---|---|---|---|---|---|
| CS-OFF-01 | Valid `CredentialOfferMessage` with fully populated `CredentialObject`s | 2xx | MUST | 6.6.1, 6.6.2 | `cs_06_06_01_credentialOfferMessage` | ✅ | | |
| CS-OFF-02 | Sparse offer (entries carry only `id`) | 2xx; CS resolves the remaining properties from the issuer's metadata | MUST | 6.6.1 | `cs_06_06_01_credentialOfferMessage_sparse` | ⚠️ | | TCK asserts 2xx acceptance only; resolution against metadata not asserted — IH resolution behavior unverified |
| CS-OFF-03 | Missing `issuer` | 400 | MUST | 6.6.1 | `cs_06_06_01_credentialOfferMessage_invalidBody` | ✅ | | |
| CS-OFF-04 | Empty `credentials` array | 400 (§6.6.1 defines `credentials` as a non-empty array) | MUST | 6.6.1 | | ❌ | | |
| CS-OFF-05 | Offer entry whose `id` cannot be resolved against the issuer's metadata | 4xx, offer produces no side effects | SHOULD | 6.6.1 | `cs_06_06_01_credentialOfferMessage_sparse_randomIds_expect400` | ✅ | | TCK enforces 4xx here despite the plan's SHOULD; side-effect absence not asserted |
| CS-OFF-06 | Identical offer sent twice | Idempotent — no duplicated side effects (an offer does not obligate the CS to auto-request; acting on it is an implementation choice) | SEC | — | | ❌ | | Tag rule |
| CS-OFF-07 | Suite TOK | — | MUST | 4.3, 4.3.3 | see §3 | see §3 | | |

### 4.3 Client behavior toward the issuer (observed via the mock Issuer Service)

The TCK's `issuance.cs` package tests only the CS's inbound APIs (Storage, Offer). **No TCK test triggers or observes the CS sending a `CredentialRequestMessage`** — verified against source; the entire suite below consists of enhanced tests (§1.3) and depends on the Credential Service trigger defined in §2.2. IH statuses come from IdentityHub's own unit/e2e coverage.

| ID | Scenario | Expected | Tag | DCP § | TCK Test | IH | DIV | Notes |
|---|---|---|---|---|---|---|---|---|
| CS-REQ-01 | CS initiates a request | Well-formed `CredentialRequestMessage`: `@context`, `type`, `holderPid`, `credentials` array of objects whose `id`s come from the issuer's `credentialsSupported` | MUST | 6.4.1 | | ✅ | | IH's own tests cover message contents |
| CS-REQ-02 | SI token on the request | `iss`=`sub`= holder DID, `aud`= issuer DID, signed with a holder key | MUST | 4.3, 6.4 | | ✅ | | Covered by IH's own tests |
| CS-REQ-03 | Endpoint discovery | Request goes to the base URL from the `IssuerService` service entry of the issuer's DID document, path `/credentials` | MUST | 6.2, 6.3 | | ✅ | | Covered by IH's own tests |
| CS-REQ-04 | Issuer responds 201 with `Location` header (and no body) | CS associates the request with the status resource — subsequent behavior (polling, correlation) works off the `Location` value | MUST | 6.4.1 | | ❌ | | |
| CS-REQ-05 | CS re-sends after a crash/timeout | Idempotent: same `holderPid`, no second logical request | SEC | — | | ❌ | | Tag rule |
| CS-REQ-06 | Issuer rejects synchronously (4xx) | CS reports the request as failed; no unbounded retries | SHOULD | — | | ✅ | | Tag rule; no spec statement on client retry behavior; covered by IH's own tests |
| CS-REQ-07 | Request accepted, then rejected post-acceptance (status endpoint returns `REJECTED`) | CS eventually reflects the rejection (via polling the status endpoint or on receipt of a `REJECTED` CredentialMessage) — the request does not stay "in progress" forever | SHOULD | — | | ❌ | | Tag rule; issuer-side REJECTED reporting is normative (§6.5.1, §6.8.1), the holder's polling duty is not |
| CS-REQ-08 | Issuer DID unresolvable, or DID document without `IssuerService` entry | Clean local failure; no message sent | MUST | 6.2 | | ✅ | | §6.2 binds discovery mechanics; the clean-failure behavior itself is not normative; both variants covered by IH's own tests |

---

## 5. Issuer Service under test

### 5.1 Endpoint discovery

| ID | Stimulus | Expected | Tag | DCP § | TCK Test | IH | DIV | Notes |
|---|---|---|---|---|---|---|---|---|
| IS-DISC-01 | Resolve the issuer's DID document and discover the `IssuerService` endpoint | `service` entry of type `IssuerService` present; endpoint reachable | MUST | 6.2 | `is_6_2_endpointDiscovery` | ✅ | | Issuer-side counterpart of CS-PRES-01 |

### 5.2 Credential Request API — `POST <IssuerService>/credentials`

| ID | Stimulus | Expected | Tag | DCP § | TCK Test | IH | DIV | Notes |
|---|---|---|---|---|---|---|---|---|
| IS-REQ-01 | Valid `CredentialRequestMessage` from an authorized holder | Exact 201 (not generic 2xx) with `Location` header pointing at the request-status resource; GET on `Location` with valid auth yields a valid `CredentialStatus`; response body not required for correlation | MUST | 6.4.1 | `is_6_4_1_credentialRequest` | ✅ | | TCK asserts 2xx + non-empty `Location` + eventual delivery to the mock CS; exact 201 and `Location` dereferenceability not asserted. IH's own tests assert 201 + `Location` |
| IS-REQ-02 | Post-acceptance failure (issuance prerequisites fail later) | 201 at receipt; failure reported through the status endpoint (`REJECTED`), not the initial response | MUST | 6.4.1, 6.5.1, 6.8.1 | | ⚠️ | | IH's `ERRORED`→`REJECTED` status mapping exists but the flow is only partially verified |
| IS-REQ-03 | Message missing required fields (`@context`, `type`, `holderPid`, `credentials`) | 400 | MUST | 6.4.1 | `is_6_4_4_credentialRequest_invalidBody`, `is_6_4_12_credentialRequest_missingHolderPid` | ✅ | | Variants remove `credentials` and `holderPid` respectively |
| IS-REQ-04 | Empty `credentials` array | 400 | MUST | 6.4.1 | | ⚠️ | | No TCK test — verified against source. §6.4.1 marks `credentials` REQUIRED but, unlike §6.6.1's offer message, does not state non-empty — spec tightening candidate. IH's rejection branch exists but is untested |
| IS-REQ-05 | `credentials[].id` not present in `credentialsSupported` | 4xx, or 201 followed by status `REJECTED` — either way observable as a failure; never silently accepted or partially processed | MUST | 6.4.1 | | ✅ | | IH's own tests cover this |
| IS-REQ-06 | Second request with the same `holderPid` from the same holder | No second issuance: 409 (or idempotent acceptance), never two deliveries | SEC | — | | ✅ | | Tag rule; IH's own tests cover this |
| IS-REQ-07 | Request with multiple credential ids | One process; all credentials delivered in a single `CredentialMessage`; `status` applies to the whole request (`REJECTED` if at least one credential cannot be issued — no partial issuance) | MUST | 6.5.1 | `is_6_4_1_credentialRequest` | ⚠️ | | TCK requests all vc11 credential ids (2 types) and awaits 2 delivered credentials — but does not assert single-message delivery or whole-request REJECTED semantics; IH batch behavior otherwise untested |
| IS-REQ-08 | Holder does not meet issuance requirements (attestations/`issuancePolicy`) — positive variant: holder holds the prerequisite credential, IS resolves the holder's CS via the access token and verifies the prerequisite VP | Negative → 4xx or 201 + status `REJECTED`; positive → issuance proceeds | MUST | 6.4, 6.6.2 | | ❌ | | IH's attestation rejection works, but `issuancePolicy` VP enforcement is unimplemented (`issuancePolicy` always empty) |
| IS-REQ-09 | Pre-authorized code flow, if supported: valid `pre-authorized_code` claim in the SI token; also wrong/expired code | Valid → 201 and issuance without further approval; invalid → 4xx | MAY | 6.4 | | — | | Tag rule; IH does not implement the pre-authorized code flow |
| IS-REQ-10 | Suite TOK | — | MUST | 4.3, 4.3.3 | see §3 | see §3 | | |

### 5.3 Credential Request Status API — `GET <IssuerService>/requests/{id}`

| ID | Stimulus | Expected | Tag | DCP § | TCK Test | IH | DIV | Notes |
|---|---|---|---|---|---|---|---|---|
| IS-STAT-01 | Requesting holder queries its own request | 200 `CredentialStatus` with `issuerPid`, `holderPid`, `status` | MUST | 6.8, 6.8.1 | `is_6_8_1_credentialStatusRequest` | ✅ | | |
| IS-STAT-02 | Queried over the request lifecycle | `status` progresses `RECEIVED` → `ISSUED` (successful delivery) or `RECEIVED` → `REJECTED` (failure); only these three values ever appear | MUST | 6.8.1 | `is_6_8_1_credentialStatusRequest` | ✅ | | TCK asserts the value is in {ISSUED, RECEIVED, REJECTED} at one poll — progression and exclusivity over the lifecycle not asserted; IH's state mapping is total |
| IS-STAT-03 | A *different* holder with a valid token queries the request | 4xx, no status data (§6.8: the IS MUST implement access control such that only the client that made the request MAY access) | MUST | 6.8 | | ✅ | | TCK tests invalid tokens on this endpoint but not a wrong principal; IH's own tests cover this |
| IS-STAT-04 | Unknown request id | 4xx; response indistinguishable from the unauthorized case (no existence leak) | SEC | — | `is_6_8_12_credentialStatusRequest_requestIdNotFound` | ✅ | | TCK asserts 4xx; indistinguishability not asserted — IH deliberately returns 401 for unknown ids, matching the unauthorized case |
| IS-STAT-05 | Suite TOK | — | MUST | 4.3, 4.3.3 | see §3 | see §3 | | |

### 5.4 Issuer Metadata API — `GET <IssuerService>/metadata`

| ID | Stimulus | Expected | Tag | DCP § | TCK Test | IH | DIV | Notes |
|---|---|---|---|---|---|---|---|---|
| IS-META-01 | Fetch metadata | 200 `IssuerMetadata`: `issuer` = issuer DID, `credentialsSupported` array of `CredentialObject`s | MUST | 6.7, 6.7.1 | `is_6_7_issuerMetadata` | ✅ | | Asserts `type`, `issuer` DID, non-empty array only |
| IS-META-02 | Inspect each `CredentialObject` | Contains **all** optional properties (`credentialType`, `bindingMethods`, `credentialSchema`, `profile`, `issuancePolicy`, `offerReason`) with meaningful, well-formed values | MUST | 6.7.1 | | ❌ | | |
| IS-META-03 | Fetch twice | `CredentialObject.id`s are stable — clients reference and cache them | MUST | — (implied by the resolution MUSTs in 6.4.1, 6.6.1) | | ✅ | | No explicit normative statement — spec tightening candidate; IH ids equal credential-definition ids, stable by construction |
| IS-META-04 | Issuer with no credential types configured | 200 with empty `credentialsSupported` | SHOULD | — | | ⚠️ | | Tag rule; `credentialsSupported` is OPTIONAL in §6.7.1; IH behavior untested |

### 5.5 Client behavior: delivery and offers (observed via the mock Credential Service)

Offer emission (IS-OFF-01/02) depends on the issuer trigger defined in §2.2.

| ID | Scenario | Expected | Tag | DCP § | TCK Test | IH | DIV | Notes |
|---|---|---|---|---|---|---|---|---|
| IS-DELIV-01 | Successful issuance | `POST` to the holder's `CredentialService` endpoint (from its DID document) at `/credentials`, with `issuerPid`, the holder's `holderPid`, `status=ISSUED`, and containers `{credentialType, payload, format}` | MUST | 5.2, 6.5.1, 6.5.2 | `is_6_4_1_credentialRequest` | ✅ | | Delivery observed by the mock CS, which validates message structure and SI token |
| IS-DELIV-02 | SI token on delivery | `iss`=`sub`= issuer DID, `aud`= holder DID | MUST | 4.3, 4.3.3 | `is_6_4_1_credentialRequest` | ✅ | | Enforced by the mock CS's token validation on receipt |
| IS-DELIV-03 | The holder's request token carried a `token` claim | The delivery token carries the same access token in its `token` claim | MUST | 6.1 | | ❌ | | |
| IS-DELIV-04 | Verify the delivered credential | Proof verifies against a key in the issuer's DID document; `issuer` property set per the selected profile's VC data model; `credentialSubject.id` = holder DID; correct `format` and `credentialType` in the container; passes full verifier-side validation with no manual fixes | MUST | 5.4.3, 6.5.2, A.2 | | ✅ | | The mock CS parses but does not cryptographically verify received credentials; IH's own generation tests verify signed output for both profiles |
| IS-DELIV-05 | Holder endpoint temporarily unavailable | Delivery retried; if permanently undeliverable, request status becomes `REJECTED` (observable via status API) | SHOULD | — | | ⚠️ | | Tag rule; no spec statement on delivery retry; IH retry path untested |
| IS-DELIV-06 | Delivery retry after an ambiguous outcome | Re-delivery carries the same `issuerPid`/`holderPid` so a compliant holder can deduplicate | SEC | — | | ⚠️ | | Tag rule; IH re-delivery contract undefined |
| IS-OFF-01 | Issuer sends an offer | Well-formed `CredentialOfferMessage` to the holder's `/offers` endpoint: `issuer` DID + non-empty `credentials`, each entry resolvable against the issuer's own metadata | MUST | 6.6.1 | | ✅ | | TCK has no trigger to make the IS emit an offer; IH's own e2e covers the full offer round trip |
| IS-OFF-02 | SI token on the offer | `aud` = holder DID | MUST | 4.3 | | ✅ | | Same; holder-side verification passes in IH's e2e |
| IS-OFF-03 | Key rotation / re-issuance scenarios, if supported | Offers carry `offerReason` = `reissue` / `proof-key-revocation` | MAY | 6.6.2, 6.9.1, 6.9.2 | | ⚠️ | | Tag rule; IH hardcodes `offerReason=reissue` regardless of scenario |

---

## 6. Presentation

Issued credentials must be usable: the Credential Service must present them correctly. This section maps the TCK's existing Presentation Query API suite in full — part of the executed set per the exit criterion (§1.2) — plus the presentation-side enhanced tests. The exit criterion applies per role: a SUT executes the suites for the roles it implements. The verifier role (the EDC connector in the IH stack, the Cofinity verification environment in the DIV stack) is out of scope for this plan; the TCK's verifier suite applies to those components under their own conformance obligations. Verifier-side validation rules still bind the SUTs' output through IS-DELIV-04 and CS-PRES-13.

### 6.1 Presentation Query API (Credential Service under test)

The harness plays the verifier: it obtains a VP access token from the CS and queries `POST <CredentialService>/presentations/query`.

| ID | Stimulus | Expected | Tag | DCP § | TCK Test | IH | DIV | Notes |
|---|---|---|---|---|---|---|---|---|
| CS-PRES-01 | Resolve the CS's DID document and discover the `CredentialService` endpoint | `service` entry of type `CredentialService` present; endpoint reachable | MUST | 5.2 | `cs_05_02_endpointDiscovery` | ✅ | | Credential Service-side counterpart of `is_6_2_endpointDiscovery` |
| CS-PRES-02 | Suite TOK on the Presentation Query API, using SI tokens carrying VP access tokens | 401 per variant | MUST | 4.3.3, 5.3.1 | `cs_04_03_03_*` (7 variants: iss/sub binding, aud, sub vs DID id, nbf, expiry, jti replay) | ✅ | | The three blank TOK variants (TOK-09–11) and TOK-13 are equally absent here |
| CS-PRES-03 | Access token submitted with a malformed header | 4xx | MUST | 5.3.1 | `cs_05_03_01_accessTokenInvalidHeader` | ✅ | | |
| CS-PRES-04 | Token not authorized for the Resolution API | 4xx | MUST | 5.4 | `cs_05_04_invalidTokenNotAuthorized` | ✅ | | |
| CS-PRES-05 | Query contains both `scope` and `presentationDefinition` | 4xx — exactly one of the two is allowed | MUST | 5.4.1, 5.4.1.2 | `cs_05_04_01_invalidScopeAndPresentationRequest` | ✅ | | |
| CS-PRES-06 | Empty `presentationDefinition` object | 4xx | MUST | 5.4.1.1 | `cs_05_04_01_emptyPresentationDefinition` | ✅ | | |
| CS-PRES-07 | Empty or absent `scope` array | 4xx | MUST | 5.4.1.2 | `cs_05_04_02_emptyScopeExpect4xx` | ✅ | | |
| CS-PRES-08 | Scope query by credential type with a matching access token | 2xx; VP containing the credential of that type | MUST | 5.4.1.2, 5.4.2 | `cs_05_04_01_02_scopeByTypeRequest` | ✅ | | |
| CS-PRES-09 | Access token authorizes more scopes than the query requests | 2xx with only the queried subset | MUST | 5.4.1.2 | `cs_05_04_01_02_lessScopesThanAuthorizedByTypeRequest` | ✅ | | |
| CS-PRES-10 | Scope escalation: query for a scope the access token does not authorize | 4xx or reduced response; no unauthorized data returned | MUST | 5.4.1.2 | `cs_05_04_01_02_invalidScopeEscalationRequest` | ✅ | | |
| CS-PRES-11 | Query with scope alias `org.eclipse.dspace.dcp.vc.id:<credential-id>` and matching access token; negative: nonexistent credential ID | 2xx with exactly that credential; negative → 2xx with empty/reduced `presentation` array, no error leakage | MUST | 5.4.1.2.2, 5.4.1.2 | | ⚠️ | | The `vc.id` alias MUST be supported; untested by the TCK and unverified in IH |
| CS-PRES-12 | Three credentials of one type loaded: valid, expired, revoked (via a status list the CS can resolve); query by type scope | Response contains only the valid credential | SHOULD | 5.4.2 | | ⚠️ | | Tag rule |
| CS-PRES-13 | Validate a returned VP externally: signature verifies against the holder DID document, referenced key carries the `authentication` relationship, VP holder matches the CS's participant DID | All generated VPs satisfy the verifier validation rules of §5.4.3 | MUST | 5.4.3 | | ⚠️ | | TCK checks VC contents but not the VP-level `authentication` relationship |

Deferred: all Presentation Definition cases, including the `501 Not Implemented` response (§5.4.1.1) for non-supporting implementations. The three PD tests in the TCK are `@Disabled` upstream (`cs_05_04_01_01_presentationRequest`, `cs_05_04_01_02_lessTypesThanAuthorizedByTypeRequest`, `cs_05_04_01_01_invalidPresentationEscalationRequest`); if enabled upstream, they join the executed set.

---

## 7. Cross-implementation round trips

Interoperability runs pairing implementations in both directions per §1.1: DIV issuer → IH Credential Service, and IH Issuer Service → DIV Credential Service. The IH column reflects IdentityHub-to-IssuerService runs; DIV pairings are recorded in the DIV column during verification.

| ID | Scenario | Expected | Tag | DCP § | TCK Test | IH | DIV | Notes |
|---|---|---|---|---|---|---|---|---|
| RT-01 | Request → issue → deliver, once per commonly supported DCP profile | Holder ends up holding a verifiable credential; issuer status reads `ISSUED`; both sides agree on `issuerPid`/`holderPid` correlation | MUST | 6.1, A.2 | `is_6_4_1_credentialRequest` (issuer side, vc11), `cs_06_05_01_credentialMessage` (holder side, vc11) | ✅ | | `vc20-bssl/jwt` never exercised by the TCK; IH's own e2e covers both profiles |
| RT-02 | Offer → request → issue | Same end state, initiated by the issuer's offer | MUST | 6.6.1 | | ✅ | | TCK tests offer reception only; auto-request is an implementation choice; IH's own e2e covers the full flow |
| RT-03 | Rejection round trip: request accepted, then rejected post-acceptance | Issuer status reads `REJECTED`; issuer delivers a `CredentialMessage` with `status: REJECTED`, correct pid correlation, no `credentials` payload required, `rejectionReason` discloses nothing confidential; holder observes the failure; nothing stored on the holder | MUST | 6.5.1, 6.8.1 | | ❌ | | Fails on the holder side (`REJECTED` treated as success, no status polling) |
| RT-04 | Batch: one request for multiple credential types | All credentials arrive in one `CredentialMessage` and are individually usable | MUST | 6.5.1 | | ⚠️ | | is_6_4_1 exercises a 2-credential request but does not assert single-message delivery; IH batch round trip untested |
| RT-05 | Revocation: (1) issued credential carries a `BitstringStatusListEntry` with resolvable `statusListCredential` URL, valid index, `statusPurpose: revocation`; (2) status list dereferences to a well-formed signed Bitstring Status List credential, bit unset; (3) revoke via management interface; (4) bit now set | End-to-end revocation works and is externally verifiable; an independent verifier rejects the revoked credential | MUST | 6.10 | | ✅ | | TCK tests verifier behavior against a TCK-hosted list only — nothing tests the issuer publishing or flipping status; IH's own tests cover publication, rollover, and the revocation round trip |
| RT-06 | Key rotation: issuer rotates its signing key | New key appears in the issuer DID document while the old `verificationMethod` is retained; credentials issued before rotation remain verifiable until expiry; newly issued credentials verify against the new key | SHOULD | 6.9.1 | | ⚠️ | | §6.9.1's MUST (retention period at least to the last credential's expiry) applies if rotation is supported; IH rotation behavior untested |
| RT-07 | Re-issuance: offer with `offerReason=reissue` before expiry | Holder obtains a fresh credential; the old one remains valid until expiry | MAY | 6.9.1 | | ✅ | | Tag rule; IH's renewal round trip and offer-initiated issuance are covered by its own e2e |

---

## 8. Base protocol, profile, and deployment cases

These cases complete the conformance surface beyond the issuance and presentation protocols: base-protocol behavior, profile requirements, and deployment concerns. No TCK tests exist for any of them; they are enhanced tests per §1.3.

| ID | Flow | Expected | Tag | DCP § | SUT |
|---|---|---|---|---|---|
| BASE-01 | Send each protocol message (a) as compacted JSON-LD with the DCP context (`https://w3id.org/dspace-dcp/v1.0/dcp.jsonld`) and (b) as semantically identical expanded/re-compacted JSON-LD; validate SUT responses against the published JSON Schemas | Both forms accepted; all SUT response bodies schema-valid with correct `@context` and `type` | MUST-adjacent | §4, "Schemas, Contexts, and Message Processing" | CS, IS, Verifier |
| BASE-02 | Verify production endpoints (CS, IS, DID documents, status list URLs) are served exclusively over TLS; plain HTTP refused or redirected with no protocol payload over HTTP | No DCP message flow completes over plaintext HTTP in production configuration | MUST | §4, "The Base URL" ("The base URL MUST use the HTTPS scheme") | CS, IS (deployment test; TCK runs HTTP by design) |
| PROF-01 | Load one `vc11-sl2021/jwt` and one `vc20-bssl/jwt` credential into the CS; query a scope covering both | Two internally homogeneous presentations — never one mixed presentation | MUST | A.2.1 (Homogeneity requirement) | CS |
| PROF-02 | Run token validation and credential verification with keys/signatures in ES256, RS256, and EdDSA (Ed25519); include unsupported/`none`-alg tokens | All three verified correctly; unsupported/`none` rejected | Expected practice | — (no normative statement in v1.0.1) | CS, IS, Verifier (TCK is P-256 only) |
| DSP-01 | GET `/.well-known/dspace-trust` on each DSP catalog; validate `@context` and `credentialsSupported` entries | 200 with schema-valid body consistent with the Issuer Metadata API | MUST | `dsp.profile.md` — see normative-standing flag below | DSP catalog / connector (Cofinity environment) |

`vc20-bssl/jwt` profile round trips are folded into CS-STOR-02 and RT-01 above.

**Normative-standing flag on DSP-01:** `dsp.profile.md` exists in the DCP v1.0.1 repository and carries the `/.well-known/dspace-trust` MUST, but it is **not included in the published v1.0.1 specification page** (the ReSpec index includes terminology, ecosystem, trust model, base protocol, VPP, CIP, and profiles only). Its contractual standing as a conformance requirement should be clarified.

---

## 9. Supplemental test summary

**Totals across §§3–7** (87 cases, excluding the per-table Suite TOK references): **30 covered** by an executing TCK test, **7 partially covered** (test listed with a caveat in Notes — supplemental assertions still needed), **50 blank** (enhanced test required per §1.3) — plus **5 additional cases** in §8. The presentation section (§6) accounts for 10 of the covered cases via the TCK's Presentation Query API suite. The TCK's verifier suite is out of scope for this plan's SUTs (§6) and is not counted.

**IH column totals:** 13 ❌, 13 ⚠️ unknown, 1 — (pre-auth n/a), the rest ✅.

**Priorities:**

- **P0 — blocks credible conformance for the Cofinity Issuer Service + BYOW Credential Services:** TOK-09/10/11; IS-REQ-01 (exact 201 + dereferenceable Location), IS-REQ-05, IS-REQ-02/RT-03 (rejection flow), IS-REQ-07/RT-04 (single-message batch + whole-request status), IS-META-02/03, IS-DELIV-04, RT-05 (revocation publication); CS-STOR-02/RT-01 and PROF-01 (`vc20-bssl/jwt`); CS-STOR-05, CS-STOR-09–12; CS-REQ-04.
- **P1 — production readiness:** TOK-13; CS-STOR-01 (holding assertion), CS-STOR-03 (remaining field omissions), CS-STOR-07, CS-STOR-14/IS-DELIV-03 (access-token echo); CS-OFF-02 (resolution assertion), CS-OFF-04; CS-REQ-01/02/03, CS-REQ-05/07; IS-REQ-04, IS-REQ-08 (issuance policy); IS-STAT-02 (lifecycle progression), IS-STAT-03; RT-02; BASE-01, CS-PRES-12, DSP-01 (after normative-standing clarification).
- **P2 — hardening:** remaining SEC/SHOULD/MAY cases (CS-STOR-06/08/13, CS-OFF-06, CS-REQ-06/08, IS-REQ-06/09, IS-STAT-04 leak-indistinguishability, IS-META-04, IS-DELIV-05/06, IS-OFF-01–03, RT-06/07), BASE-02, CS-PRES-11, CS-PRES-13, PROF-02.

**Spec tightening candidates surfaced by the tracing** (issues for the CIP/VPP editors): non-empty constraint missing on `CredentialRequestMessage.credentials` (present for offers, §6.6.1, absent in §6.4.1); Bearer-header MUST stated only for §6.4/§6.8 within the issuance protocol; `CredentialObject.id` stability across metadata fetches implied but never stated; delivery retry behavior unspecified.

**Versioning note:** TCK test IDs above are from `main` (v1.0.3-SNAPSHOT). Pin the TCK release for conformance gating and re-verify the IDs against the pinned tag. The three `@Disabled` PD tests may be enabled upstream, which would change the pass bar.
