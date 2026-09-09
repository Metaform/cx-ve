# Registration API Test Plan

<!-- TOC -->

* [Registration API Test Plan](#registration-api-test-plan)
    * [1. Introduction](#1-introduction)
        * [1.1 Purpose](#11-purpose)
        * [1.2 Test basis and approach](#12-test-basis-and-approach)
        * [1.3 Document conventions](#13-document-conventions)
    * [2. Requirements on the system under test](#2-requirements-on-the-system-under-test)
    * [3. Suite AUTH — authentication and authorization (shared)](#3-suite-auth--authentication-and-authorization-shared)
    * [4. Suite REG — partner registration —
      `POST /api/administration/registration/network/partnerregistration`](#4-suite-reg--partner-registration--post-apiadministrationregistrationnetworkpartnerregistration)
        * [4.1 Happy path and optional fields](#41-happy-path-and-optional-fields)
        * [4.2 Structural and schema validation](#42-structural-and-schema-validation)
        * [4.3 Business rules and referential integrity](#43-business-rules-and-referential-integrity)
    * [5. Suite TEN — tenant registration —
      `POST /api/administration/osp/v2/tenant-registration`](#5-suite-ten--tenant-registration--post-apiadministrationospv2tenant-registration)
    * [6. Suite FILE — file upload —
      `POST /api/administration/osp/v2/tenant-registration/fileupload`](#6-suite-file--file-upload--post-apiadministrationospv2tenant-registrationfileupload)
    * [7. Suite CBC — callback configuration — `GET`/
      `POST /api/administration/registrationstatus/callback`](#7-suite-cbc--callback-configuration--getpost-apiadministrationregistrationstatuscallback)
    * [8. Suite OSP — status callback receiver —
      `POST {callbackUrl}`](#8-suite-osp--status-callback-receiver--post-callbackurl)
        * [8.1 CSP-B client behavior (observed via a mock OSP receiver)](#81-csp-b-client-behavior-observed-via-a-mock-osp-receiver)
    * [9. Suite E2E — end-to-end flows](#9-suite-e2e--end-to-end-flows)
    * [10. Spec observations](#10-spec-observations)

<!-- TOC -->

## 1. Introduction

### 1.1 Purpose

This plan defines the test cases necessary to verify correct behavior of the **CX-0009 CX Registration API v2.1.0** as
specified in [
`the Catena-X Standards document`](https://github.com/catenax-eV/product-standardization-prod/blob/R26.09-CX-0009-CX-Registration-API/standards/CX-0009-CXRegistrationAPI/CX-0009-CXRegistrationAPI.md)
and its accompanying OpenAPI documents (`assets/registration-api.yaml`, `assets/osp-registration-callback-api.yaml`),
which the spec declares normative (spec §2.1.1). The API has two implementation roles:

1. **Core Service Provider B (CSP-B)** implements the partner registration endpoint (spec §2.2.1), the tenant
   registration endpoint (spec §2.2.2), the file upload endpoint (spec §2.2.3), and the callback configuration
   endpoints (spec §2.2.4, §2.2.5).
2. **Onboarding Service Provider (OSP)** implements the registration status callback receiver (spec §2.3.1), which
   CSP-B calls as a client.

Both roles are covered. For the CSP-B endpoints the system under test is the CSP-B implementation and the test harness
acts as an OSP client (plus a mock OSP callback receiver to observe outbound callbacks). For the callback receiver the
SUT is the OSP implementation and the harness acts as CSP-B.

### 1.2 Test basis and approach

The test basis is the standards **markdown document** together with the two **normative OpenAPI documents**. The spec
states the OpenAPI documents "MUST remain aligned with this specification" — where they currently diverge (see §10.3),
this plan treats the markdown as authoritative and records the misalignment as a finding. Test cases were derived from
three sources, in order of authority:

1. **Declared endpoint behavior** — the paths, field tables (Mandatory/Optional), response codes, state machines
   (spec §3), and the OpenAPI schema constraints (`required`, enums, formats, `additionalProperties`, `contains`).
2. **General normative statements** — `application/json` MUST be used for control-plane messages (spec §2.1.1),
   authentication and authorization MUST be implemented (spec §2.1.2), standard HTTP response codes MUST be used
   (spec §2.1.3).
3. **Expected production behavior** — hardening (idempotency, tenant isolation, secret handling, SSRF) and behavior
   the spec leaves undefined but which any real implementation must pin down.

Each case asserts only externally observable behavior: HTTP status codes, response bodies, side effects reachable
through the API (e.g. a registration's status callbacks), and effects on the file storage reachable via the presigned
URL. Implementation internals are out of scope.

### 1.3 Document conventions

| Tag    | Meaning                                                                                                                |
|--------|------------------------------------------------------------------------------------------------------------------------|
| `SPEC` | Traces directly to declared behavior (markdown field table/response table, state machine, or normative OpenAPI schema) |
| `SEC`  | Security/robustness hardening expected of a production implementation; not stated in the spec                          |
| `GAP`  | Behavior the spec leaves undefined; the test pins expected behavior and flags a spec omission (§10)                    |

- Where a test expects `400`/`4xx`, the exact code is implementation-specific unless the spec declares one; the
  assertion is that the request is rejected with a client-error code and produces **no side effects**. Where the
  OpenAPI declares an `ErrorResponse` body for the error (fileupload 400, callback-config 400s), its shape is asserted
  too.
- Most schemas now declare `additionalProperties: true` (§10.6): unknown extra properties are **schema-valid** and the
  matching cases assert tolerant-reader behavior (accepted and ignored), not rejection.
- "The example payload" refers to the request example embedded in the spec for the endpoint under test. Examples are
  non-normative (spec §1.4).
- Cases marked *(behavior)* record actual behavior on first execution and turn it into a fixed assertion afterwards.

---

## 2. Requirements on the system under test

- **CSP-B role:** a deployment with at least three test companies — two holding the `ONBOARDING_SERVICE_PROVIDER`
  company role (for tenant-isolation cases) and one without it — and per-company users whose tokens do and do not carry
  the `configure_partner_registration` role. A reachable mock OSP callback receiver for observing outbound status
  callbacks, plus a token endpoint the CSP-B can be pointed at via `authUrl`.
- **OSP role:** a deployed callback endpoint and its token endpoint; a way to seed known `externalId`s for **both**
  origination paths (spec §2.2.1 and §2.2.2), since the allowed `applicationStatus` enum depends on which endpoint
  created the registration.
- **Repeatable state.** The callback configuration is set-or-update with no delete (§10.9): a `POST` overwrites the
  previous value. Suites that set the callback (CBC, E2E) must not run concurrently against the same deployment.
- **Storage access.** The presigned-URL cases (FILE-08..11) require outbound HTTP access to the storage backend the
  URL points at, and the storage-provider-specific PUT headers, which the CSP-B must provide out of band (spec §2.2.3
  — §10.10).

---

## 3. Suite AUTH — authentication and authorization (shared)

The spec requires authentication and authorization on every endpoint (spec §2.1.2); the normative OpenAPI declares a
Bearer/JWT scheme for the CSP-B API (modeled as `type: apiKey` — §10.7). How the `configure_partner_registration` role
is conveyed remains undefined (§10.7). Run this suite against **each** CSP-B endpoint: `POST …/partnerregistration`,
`POST …/osp/v2/tenant-registration`, `POST …/osp/v2/tenant-registration/fileupload`,
`GET …/registrationstatus/callback`, `POST …/registrationstatus/callback`. (The OSP callback receiver has its own auth
cases, OSP-11..12.)

| ID      | Stimulus                                                              | Expected                                      | Tag  | Notes                                                                                                                      |
|---------|-----------------------------------------------------------------------|-----------------------------------------------|------|----------------------------------------------------------------------------------------------------------------------------|
| AUTH-01 | Valid Bearer token carrying the `configure_partner_registration` role | Request proceeds (per-endpoint success code)  | SPEC | Baseline; every non-AUTH case implicitly includes this                                                                     |
| AUTH-02 | No `Authorization` header                                             | 401                                           | SPEC |                                                                                                                            |
| AUTH-03 | `Authorization` header without `Bearer` prefix                        | 401                                           | SPEC |                                                                                                                            |
| AUTH-04 | Expired token                                                         | 401                                           | SPEC |                                                                                                                            |
| AUTH-05 | Token with invalid signature / not from the trusted issuer            | 401                                           | SPEC |                                                                                                                            |
| AUTH-06 | Valid token **without** the `configure_partner_registration` role     | 401 or 403; request rejected, no side effects | GAP  | Spec declares only 401; 403 is the conventional code for missing role. Pin actual behavior; spec should declare it (§10.7) |
| AUTH-07 | Token for a different audience/client than this API                   | 401                                           | SEC  | Only applicable if the deployment enforces audience                                                                        |

---

## 4. Suite REG — partner registration — `POST /api/administration/registration/network/partnerregistration`

Spec §2.2.1 (the "legacy" portal-completed flow). Declared responses: `200` (no body), `401`, `500` — no `400` or
`409` despite nine mandatory fields, and unlike §2.2.2's `201`/`409` (§10.2). Mandatory (markdown and the schema's
`required` now agree): `externalId`, `name`, `city`, `streetName`, `countryAlpha2Code`, `region`, `companyRoles`,
`uniqueIds`, `userDetails`. The schema additionally declares `did` and `autoSubmit`, which the markdown field table
still omits (§10.3); the former `agreements`/`fileIds` schema fields are gone — files correlate solely via
`externalId` at upload time. The schema declares `additionalProperties: true` (tolerant reader, §10.6).

### 4.1 Happy path and optional fields

| ID     | Stimulus                                                                                                               | Expected                                                                                                                                            | Tag  | Notes                                                                                                     |
|--------|--------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|------|------------------------------------------------------------------------------------------------------------|
| REG-01 | The example payload (full, valid)                                                                                      | 200, no body; registration exists — the `externalId` subsequently appears in status callbacks (E2E-01)                                              | SPEC |                                                                                                            |
| REG-02 | Valid payload with all optional fields **omitted** (`bpn`, `shortName`, `streetNumber`, `streetAdditional`, `zipCode`) | 200                                                                                                                                                 | SPEC | Distinct `externalId` per case throughout this suite                                                       |
| REG-03 | Valid payload with optional (schema-`nullable`) fields explicitly `null`                                               | 200 — explicit `null` and omission are equivalent                                                                                                   | SPEC | Per the schema's `nullable` declarations                                                                   |
| REG-04 | `did` populated with a valid DID (e.g. `did:web:…`)                                                                    | 200; the DID is associated with the registration                                                                                                    | SPEC | Declared in the schema, absent from the markdown field table (§10.3)                                       |
| REG-05 | `autoSubmit: true` with a complete payload                                                                             | 200; the registration is submitted without further interaction (see E2E-03)                                                                         | SPEC | Declared in the schema, absent from the markdown field table (§10.3)                                       |
| REG-06 | `autoSubmit: true` with an incomplete payload                                                                          | *(behavior)* — either rejected up front (4xx) or accepted with the process halting before submission; must not silently auto-submit incomplete data | GAP  | No failure mode defined                                                                                    |

### 4.2 Structural and schema validation

| ID     | Stimulus                                                                                                                                                                      | Expected                                                                    | Tag  | Notes                                                                                                              |
|--------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|------|----------------------------------------------------------------------------------------------------------------------|
| REG-10 | Omit each mandatory field in turn (`externalId`, `name`, `city`, `streetName`, `countryAlpha2Code`, `region`, `companyRoles`, `uniqueIds`, `userDetails`)                     | 400, nothing created                                                        | SPEC | Schema `required` now aligned with the markdown; the endpoint declares no 400, so the exact code is pinned by test |
| REG-11 | Empty request body / body absent                                                                                                                                              | 400                                                                         | SPEC | All-mandatory-fields-missing case of REG-10; `requestBody` is required                                             |
| REG-12 | Malformed JSON body                                                                                                                                                           | 400                                                                         | SPEC | §2.1.1 requires `application/json`                                                                                 |
| REG-13 | `Content-Type` other than `application/json` (e.g. `text/plain` with a JSON body)                                                                                             | 415 or 400                                                                  | SPEC | §2.1.1; exact code undeclared                                                                                      |
| REG-14 | Payload with unknown extra properties                                                                                                                                         | 200 — extras tolerated and ignored, no side effects from them               | SPEC | `additionalProperties: true`; the flip from strict to tolerant is undocumented (§10.6)                             |
| REG-15 | `companyRoles` containing a value outside the `CompanyRoleId` enum                                                                                                            | 400, nothing created                                                        | SPEC |                                                                                                                    |
| REG-16 | `companyRoles: []` (empty array)                                                                                                                                              | *(behavior)* — expected 400: a registration without any role is meaningless | GAP  |                                                                                                                    |
| REG-17 | Each valid `CompanyRoleId` value accepted (`ACTIVE_PARTICIPANT`, `APP_PROVIDER`, `SERVICE_PROVIDER`, `OPERATOR`, `ONBOARDING_SERVICE_PROVIDER`), incl. multiple roles at once | 200                                                                         | SPEC |                                                                                                                    |
| REG-18 | `uniqueIds[].type` outside the `UniqueIdentifierId` enum                                                                                                                      | 400, nothing created                                                        | SPEC |                                                                                                                    |
| REG-19 | Each valid `UniqueIdentifierId` value accepted (`COMMERCIAL_REG_NUMBER`, `VAT_ID`, `LEI_CODE`, `VIES`, `EORI`)                                                                | 200                                                                         | SPEC |                                                                                                                    |
| REG-20 | `uniqueIds` entry missing `type` or `value`; `uniqueIds: []`                                                                                                                  | 400, nothing created                                                        | SPEC | Both fields `required` (markdown and schema agree); empty-array semantics undeclared — expected 400                |
| REG-21 | `userDetails` entry omitting each mandatory field in turn (`providerId`, `firstName`, `lastName`, `email`)                                                                    | 400, nothing created                                                        | SPEC | Per `UserDetailData` (markdown and schema agree)                                                                   |
| REG-22 | `userDetails` entry omitting the optional fields (`identityProviderId`, `username`)                                                                                           | 200; without `identityProviderId` the OSP's default IdP is used             | SPEC | Default-IdP behavior declared in `UserDetailData`                                                                  |
| REG-23 | `userDetails[].identityProviderId` not a UUID                                                                                                                                 | 400, nothing created                                                        | SPEC | `format: uuid`                                                                                                     |
| REG-24 | `userDetails[].email` not a syntactically valid email address                                                                                                                 | *(behavior)* — expected 400                                                 | GAP  | Declared as plain string                                                                                           |
| REG-25 | `userDetails: []` (empty array)                                                                                                                                               | *(behavior)* — expected 400: at least one user is needed to onboard         | GAP  |                                                                                                                    |
| REG-26 | `countryAlpha2Code` not ISO 3166-1 alpha-2 (`"DEU"`, `"ZZ"`, `"de"`)                                                                                                          | 400, nothing created                                                        | SPEC | The field table declares the ISO 3166-1 alpha-2 constraint                                                         |
| REG-27 | `bpn` not matching the BPNL format (e.g. `"not-a-bpn"`)                                                                                                                       | *(behavior)* — expected 400                                                 | GAP  | No format declared                                                                                                 |
| REG-28 | `did` populated with a malformed DID (`"not:a:did"`)                                                                                                                          | *(behavior)* — expected 400                                                 | GAP  | No syntax constraint declared                                                                                      |
| REG-29 | Very long string values (e.g. 10 000-char `name`)                                                                                                                             | 400 or truncation-free acceptance; no 500, no partial write                 | SEC  | No length limits declared anywhere (§10.10)                                                                        |

### 4.3 Business rules and referential integrity

| ID     | Stimulus                                                                             | Expected                                                                                                                             | Tag  | Notes                                                                                                                                     |
|--------|----------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|------|---------------------------------------------------------------------------------------------------------------------------------------------|
| REG-40 | Second registration with the same `externalId` as an existing one                    | 4xx (conflict) **or** idempotent success — but never a duplicate registration                                                        | GAP  | §2.2.2 declares `409` for exactly this; §2.2.1 declares nothing — inconsistent (§10.2). `externalId` is the only callback correlation key |
| REG-41 | Registration whose files (correlated via `externalId` at upload time) are incomplete | *(behavior)* — "All files MUST be successfully uploaded before the registration request is submitted" (spec §2.2.3); pin enforcement | GAP  | The rule is stated but unenforceable as written: nothing declares how many files a registration expects (§10.10)                          |
| REG-42 | Registration submitted by a company that is not an OSP                               | *(behavior)* — expected 4xx                                                                                                          | GAP  | The flow presumes an OSP caller, but the spec gates only on the user role                                                                 |

---

## 5. Suite TEN — tenant registration — `POST /api/administration/osp/v2/tenant-registration`

Spec §2.2.2 (the fully OSP-mediated flow; company roles are excluded by design — implied Data Provider/Consumer only).
Declared responses: `201`, `401`, `409` (duplicate `externalId` for this OSP), `500`. Mandatory (markdown and schema
agree): `externalId`, `name`, `city`, `streetName`, `countryAlpha2Code`, `uniqueIds`, `userDetails`, and now
**`consents`** — which MUST contain all three required kinds (`CX_OPERATING_MODEL`, `CX_TEN_GOLDEN_RULES`,
`CX_DATA_EXCHANGE_GOVERNANCE`), machine-enforced in the schema via `minItems: 3` plus per-kind `contains` clauses.
Optional: `region` (Mandatory in §2.2.1 — §10.1), `shortName`, `streetNumber`, `streetAdditional`, `zipCode`, `did`.
The schema declares `additionalProperties: true` (§10.6) and the request body is required. Note: **no read endpoint
exists** for tenant registrations (§10.1) — outcomes are observable only through status callbacks.

| ID     | Stimulus                                                                                                                      | Expected                                                                                           | Tag  | Notes                                                                                                          |
|--------|--------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|------|--------------------------------------------------------------------------------------------------------------------|
| TEN-01 | The example payload (full, valid, all three consent kinds)                                                                    | 201; registration exists and proceeds through `OSPTenantRegistrationStatus` via callbacks (E2E-04) | SPEC | No response body or `Location` header is declared for the 201, and there is no status GET (§10.1)              |
| TEN-02 | Second POST with the same `externalId` for the same OSP                                                                       | 409, no duplicate created                                                                          | SPEC |                                                                                                                |
| TEN-03 | Same `externalId` used by a **different** OSP                                                                                 | 201 — uniqueness is per OSP; no cross-tenant collision                                             | SPEC | "already exists with this `externalId` **for this OSP**"; requires two OSP identities                          |
| TEN-04 | Omit each mandatory field in turn (incl. `consents`)                                                                          | 400, nothing created                                                                               | SPEC | Schema `required` aligned with the markdown; exact code undeclared (§10.2)                                     |
| TEN-05 | All optional fields omitted (`region`, `shortName`, `streetNumber`, `streetAdditional`, `zipCode`, `did`)                     | 201                                                                                                | SPEC | `region` Optional here, Mandatory in §2.2.1 (§10.1)                                                            |
| TEN-06 | Payload with unknown extra properties — deliberately including §2.2.1-only fields (`companyRoles`, `bpn`, `autoSubmit`)       | 201 — extras tolerated and ignored                                                                 | SPEC | `additionalProperties: true`; a silently ignored `companyRoles` is a real foot-gun worth flagging (§10.6)      |
| TEN-07 | Empty body / malformed JSON                                                                                                   | 400                                                                                                | SPEC | `requestBody` is required                                                                                      |
| TEN-08 | `consents` with exactly the three required kinds; and with the three plus an additional `OTHER` (with `fileIds`)              | 201 in both cases                                                                                  | SPEC |                                                                                                                |
| TEN-09 | `consents` containing only two of the three required kinds (each combination)                                                 | 400, nothing created                                                                               | SPEC | Violates the schema's `contains` clauses / the markdown's "MUST include all three required kinds"              |
| TEN-10 | `consents` entry without `kind`                                                                                               | 400, nothing created                                                                               | SPEC | `Consent.kind` is `required` (now aligned)                                                                     |
| TEN-11 | `consents[].kind` outside the `ConsentKind` enum                                                                              | 400, nothing created                                                                               | SPEC |                                                                                                                |
| TEN-12 | `kind: OTHER` **without** `fileIds` (alongside the three required kinds)                                                      | 400, nothing created                                                                               | SPEC | Markdown MUST; not expressible in the schema — record if the implementation misses it. What `OTHER` refers to remains unstated |
| TEN-13 | `consents[].fileIds` referencing ids not obtained from the fileupload endpoint, or files never uploaded                       | 4xx, nothing created                                                                               | SEC  |                                                                                                                |
| TEN-14 | Malformed `did` (`"not:a:did"`); valid `did:web` accepted                                                                     | 400 for malformed; 201 for valid                                                                   | GAP  | No syntax constraint declared; "when required by the CSP-B configuration" is undiscoverable by the OSP (§10.10) |

---

## 6. Suite FILE — file upload — `POST /api/administration/osp/v2/tenant-registration/fileupload`

Spec §2.2.3. Conditionally normative: MUST if files are required and provided by the OSP, MAY otherwise — with no way
for an OSP to discover which case applies (§10.10). Request (all Mandatory, `additionalProperties: true`): `fileName`,
`externalId`, `contentType` (RFC 2046). Response: `fileId`, `presignedUploadUrl` (`format: uri`) Mandatory; `expiresAt`
(`format: date-time`, nullable). Declared responses: `200`, `400` (`ErrorResponse`), `401`, `500`. The upload itself is
an HTTP **PUT** of the binary content to the presigned URL; the required headers are storage-provider-specific and
provided out of band (§10.10). On expiry, the OSP MAY request a new URL and retry.

| ID      | Stimulus                                                                                                    | Expected                                                                                                                                                   | Tag  | Notes                                                                                                |
|---------|-------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|------|------------------------------------------------------------------------------------------------------|
| FILE-01 | Valid request (`fileName`, `externalId`, `contentType`)                                                     | 200; body contains non-empty `fileId` and `presignedUploadUrl` (valid absolute URI); `expiresAt`, when present, is a valid RFC 3339 date-time in the future | SPEC |                                                                                                      |
| FILE-02 | Missing `fileName`, missing `externalId`, or missing `contentType` (each in turn)                           | 400 with `ErrorResponse`                                                                                                                                   | SPEC | All three `required`                                                                                 |
| FILE-03 | Empty-string values for any of the three fields                                                             | 400                                                                                                                                                        | GAP  | Satisfies the schema but is meaningless; expected rejection                                          |
| FILE-04 | Unknown extra property                                                                                      | 200 — extras tolerated and ignored                                                                                                                         | SPEC | `additionalProperties: true` (§10.6)                                                                 |
| FILE-05 | Malformed JSON / empty body                                                                                 | 400                                                                                                                                                        | SPEC | `requestBody` is required                                                                            |
| FILE-06 | `externalId` that no registration exists for (yet)                                                          | *(behavior)* — likely accepted: §2.2.3 requires uploads **before** the registration is submitted; pin it                                                   | GAP  | Correlation can only be validated later — or never (§10.10)                                          |
| FILE-07 | Two consecutive valid requests                                                                              | Two distinct `fileId`s; both URLs independently usable                                                                                                     | SPEC | `fileId` is described as unique                                                                      |
| FILE-08 | HTTP `PUT` of binary content to the `presignedUploadUrl` (with the storage-provider headers), before expiry | Storage accepts the upload (2xx); the file is subsequently associated with the registration for that `externalId` (E2E-04)                                 | SPEC | PUT is normative; the header set is out-of-band and must be recorded per deployment (§10.10)         |
| FILE-09 | `PUT` to the `presignedUploadUrl` **after** `expiresAt`                                                     | Rejected by storage (4xx)                                                                                                                                  | SPEC | `expiresAt` is Optional even though the prose says the URL "expires after a limited time" (§10.10)   |
| FILE-10 | After expiry, call the fileupload endpoint again for the same file and retry the upload                     | 200 with a fresh `presignedUploadUrl`; the retried upload succeeds                                                                                         | SPEC | Declared recovery path ("the OSP MAY call this endpoint again"); record whether the `fileId` changes |
| FILE-11 | The presigned URL manipulated (token stripped/altered, object path changed)                                 | Rejected by storage; the URL grants access to exactly one object                                                                                           | SEC  |                                                                                                      |
| FILE-12 | `contentType` of an unexpected/dangerous type (`text/html`, `application/x-msdownload`)                     | *(behavior)* — expected 400 if an allow-list exists; otherwise record acceptance                                                                           | GAP  | No permitted types or size limits specified (§10.10)                                                 |
| FILE-13 | `fileName` containing path separators / traversal (`"../../etc/passwd"`, `"a/b.pdf"`)                       | Rejected, or sanitized such that the name has no path effect                                                                                               | SEC  |                                                                                                      |
| FILE-14 | `PUT` with a `Content-Type` different from the one declared in the request                                  | *(behavior)* — expected rejection by storage (presigned URLs are typically content-type-bound)                                                             | SEC  |                                                                                                      |
| FILE-15 | `externalId` belonging to a **different** OSP's registration                                                | 4xx, no file associated                                                                                                                                    | SEC  | Cross-tenant file attachment must be rejected (§10.8); requires two OSP identities                   |
| FILE-16 | Files uploaded for an `externalId` whose registration is never submitted                                    | *(behavior)* — orphaned uploads must not accumulate indefinitely (TTL/cleanup); no interference with later registrations                                   | SEC  |                                                                                                      |

---

## 7. Suite CBC — callback configuration — `GET`/`POST /api/administration/registrationstatus/callback`

Spec §2.2.4 (POST, "sets or updates") and §2.2.5 (GET). Schemas:
`OnboardingServiceProviderCallbackConfigurationRequest` (all four fields `required` — markdown and schema agree) and
`…Response` (`callbackUrl`, `authUrl`, `clientId` — no secret). Declared responses — POST: `204`, `400`, `401`, `500`;
GET: `200` ("Empty if not configured"), `400`, `401`, `500`. The markdown's GET-400 is "Bad Request - invalid input"
while the normative OpenAPI's GET-400 is "The company is not an Onboarding Service Provider" (§10.3). How a
configuration is correlated to an OSP is still unstated (§10.8), and there is no DELETE (§10.9). Do not run this suite
concurrently with anything else that sets the callback.

| ID     | Stimulus                                                                                                             | Expected                                                                                                   | Tag  | Notes                                                                                                          |
|--------|----------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|------|------------------------------------------------------------------------------------------------------------------|
| CBC-01 | POST a full valid configuration as an OSP company                                                                    | 204, no body                                                                                               | SPEC |                                                                                                                |
| CBC-02 | GET after CBC-01                                                                                                     | 200; body contains `callbackUrl`, `authUrl`, `clientId` with the stored values                             | SPEC | Response schema is tolerant (`additionalProperties: true`), so assert presence, not exhaustiveness             |
| CBC-03 | GET response inspected for the secret                                                                                | `clientSecret` absent — under any spelling or masking                                                      | SPEC | "Returns the configured callback data except secret"; the tolerant response schema makes this assertion matter |
| CBC-04 | POST a second, different configuration; then GET                                                                     | 204; GET returns the **new** values — set-or-update, previous config fully replaced                        | SPEC |                                                                                                                |
| CBC-05 | POST, then trigger a status callback (via E2E flow)                                                                  | The callback is delivered to the **new** URL with a token from the **new** `authUrl`/credentials           | SPEC | Verifies the update is effective, not just stored                                                              |
| CBC-06 | POST omitting each field in turn (`callbackUrl`, `authUrl`, `clientId`, `clientSecret`)                              | 400 with `ErrorResponse`, nothing stored/changed                                                           | SPEC | All four fields `required` (markdown and schema agree)                                                         |
| CBC-07 | POST an empty object `{}`                                                                                            | 400, existing configuration unchanged                                                                      | SPEC | All-fields-missing case of CBC-06; must not clear the configuration; `requestBody` is required                 |
| CBC-08 | POST with `callbackUrl` or `authUrl` not a valid absolute URL (`"not a url"`, relative path)                         | 400, nothing stored                                                                                        | GAP  | Declared 400 is "invalid input", but URL validation rules are unstated                                         |
| CBC-09 | POST with `callbackUrl` using `http://` (non-TLS)                                                                    | *(behavior)* — expected 400 in production profiles                                                         | SEC  | TLS is never a MUST in either direction (§10.11)                                                               |
| CBC-10 | POST with an unknown extra property                                                                                  | 204 — extras tolerated and ignored                                                                         | SPEC | `additionalProperties: true` (§10.6)                                                                           |
| CBC-11 | GET before any configuration has ever been POSTed (fresh deployment/OSP)                                             | 200 with empty content; must not 500                                                                       | SPEC | Declared: "Empty if not configured" — whether that means `{}` or an empty body is unstated; record it          |
| CBC-12 | GET as a company **without** the `ONBOARDING_SERVICE_PROVIDER` company role; POST as the same                        | GET: 400 with `ErrorResponse` (declared in the OpenAPI); POST: *(behavior)* — expected 4xx, nothing stored | GAP  | The OSP-gating 400 exists only in the OpenAPI GET description, not the markdown, and not for POST (§10.3)      |
| CBC-13 | POST with `callbackUrl` pointing at internal/link-local addresses (`http://localhost/…`, `http://169.254.169.254/…`) | Rejected, or outbound calls constrained so the CSP-B cannot be used for SSRF                               | SEC  | CSP-B later POSTs to this URL server-side; same for `authUrl` (§10.11)                                         |
| CBC-14 | Two OSP companies each POST their own configuration; a registration of OSP-A reaches a status change                 | The callback goes to OSP-A's URL only; OSP-B's configuration is unaffected and receives nothing            | SEC  | Per-OSP scoping is not stated (§10.8); if the config is global, this test fails and the spec must be fixed     |

---

## 8. Suite OSP — status callback receiver — `POST {callbackUrl}`

Spec §2.3.1 plus the normative `osp-registration-callback-api.yaml`. SUT: the **OSP** implementation. Declared
responses: `200`, `401`, `500`. All callbacks use a **single schema**, `OspRegistrationCallbackData`: `externalId`
(required), `applicationStatus` (required; `RegistrationStatus` values for §2.2.1-originated registrations,
`OSPTenantRegistrationStatus` values for §2.2.2-originated ones), `message` (optional), **`bpnl` (required — §10.5)**,
`bpna`/`bpns` (optional, per CX-0010). The `applicationStatus` union is now `anyOf` (the former `oneOf` defect is
fixed), but the enums fully overlap on `SUBMITTED`/`CONFIRMED`/`DECLINED` with no discriminator, so origin distinction
rests on receiver-side tracking (§10.4). The callback's auth is declared: OAuth2 client-credentials against the configured `authUrl`,
bearer token, scope `callback` (§10.7). The harness plays CSP-B; known `externalId`s for both origination paths must
be seeded first (see §2).

| ID     | Stimulus                                                                                                          | Expected                                                                              | Tag  | Notes                                                                                                                      |
|--------|--------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|------|------------------------------------------------------------------------------------------------------------------------------|
| OSP-01 | Legacy callback, `applicationStatus: SUBMITTED`, known §2.2.1-originated `externalId`, `bpnl` present              | 200; the registration is observably in submitted state on the OSP side                | SPEC |                                                                                                                            |
| OSP-02 | Legacy callback, `applicationStatus: CONFIRMED` with `bpnl`                                                        | 200; registration confirmed; `bpnl` retained                                          | SPEC |                                                                                                                            |
| OSP-03 | Legacy callback, `applicationStatus: DECLINED` with a non-null `message`                                           | 200; registration declined and the message retained/surfaced                          | SPEC | Failure status is `DECLINED` in **both** flows again (`REJECTED` from the prior revision is gone)                          |
| OSP-04 | Legacy callbacks `CREATED` and `INVITE_USER` delivered in legal order                                              | 200; state tracked consistently                                                       | SPEC | Whether CSP-B ever emits these is undefined (§10.5, OSP-24) — the receiver must tolerate them regardless                   |
| OSP-05 | Tenant callback: `DECLINED` with `message`; `CONFIRMED` with `bpnl`, `bpna`, `bpns` populated                      | 200; message and all BPN values retained                                              | SPEC | `bpna`/`bpns` reference CX-0010; record observed semantics                                                                 |
| OSP-06 | `message: null`, and `message` omitted                                                                             | 200 in both cases                                                                     | SPEC | Optional field                                                                                                             |
| OSP-07 | Unknown extra properties in the payload                                                                            | 200 — extras tolerated and ignored                                                    | SPEC | Single schema, tolerant by default (§10.6); the prior request/tenant tolerance asymmetry is gone                           |
| OSP-08 | Missing `externalId`, missing `applicationStatus`, or missing `bpnl`                                               | 400, no state change                                                                  | GAP  | All three `required`, but the endpoint declares no 400 (§10.2); mandatory `bpnl` on early statuses is itself suspect (§10.5) |
| OSP-09 | `applicationStatus` outside both enums — use `"REJECTED"`                                                          | 400, no state change                                                                  | GAP  | `REJECTED` was a valid value in the prior revision and appears in no enum now — a deliberate regression probe              |
| OSP-10 | Well-formed callback with an **unknown** `externalId`                                                              | 4xx, no state change; must not 500 and must not create phantom state                  | GAP  | Spec declares only 200/401/500                                                                                             |
| OSP-11 | No `Authorization` header, or a token not obtained from the configured credentials                                 | 401, no state change                                                                  | SPEC | The OpenAPI now declares the client-credentials scheme with scope `callback`; what the OSP must validate is still prose-free (§10.7) |
| OSP-12 | Expired or invalid-signature token                                                                                 | 401                                                                                   | SPEC |                                                                                                                            |
| OSP-13 | Exact duplicate delivery (same `externalId` + `applicationStatus` twice)                                           | Idempotent: 200, no duplicated side effects                                           | SEC  | CSP-B retry behavior is undefined (§10.11), so at-least-once delivery must be assumed                                      |
| OSP-14 | Transition violating the declared state machines (e.g. `SUBMITTED` after `CONFIRMED`; `DECLINED` after `CONFIRMED`) | Ignored or rejected — state remains consistent, no 500                                | SPEC | Transitions are normative (spec §3, "any **non-terminal** state -> DECLINED"); receiver enforcement duty unstated (§10.5)  |
| OSP-15 | `applicationStatus: CREATED` or `INVITE_USER` for a **tenant-originated** `externalId`                             | 400 or ignored, no state corruption                                                   | GAP  | The only observable wrong-enum case — the enums otherwise fully overlap now, so origin distinction rests entirely on receiver-side tracking (§10.4) |

### 8.1 CSP-B client behavior (observed via a mock OSP receiver)

These cases test CSP-B as the callback **client**; the harness's mock OSP records what arrives.

| ID     | Scenario                                       | Expected                                                                                                                                                                              | Tag  | Notes                                                                                                          |
|--------|------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|------------------------------------------------------------------------------------------------------------------|
| OSP-20 | A registration reaches a status change         | CSP-B POSTs to exactly the configured `callbackUrl`; the payload conforms to `OspRegistrationCallbackData` with the original `externalId`, a legal `applicationStatus` for the origin, and `bpnl` present | SPEC |                                                                                                                |
| OSP-21 | Token acquisition                              | CSP-B obtains a token from the configured `authUrl` via OAuth2 client-credentials (`clientId`/`clientSecret`) and sends it as a Bearer token                                          | SPEC | Now normatively declared in the callback OpenAPI's security scheme — resolved from the prior revision          |
| OSP-22 | Mock OSP responds 500 or is unreachable        | *(behavior)* — expected retry with backoff; the status change must not be silently lost                                                                                              | GAP  | Retry semantics undeclared (§10.11); with no read endpoint, a lost callback is unrecoverable (§10.1)           |
| OSP-23 | Mock OSP responds 401 (e.g. rotated secret)    | *(behavior)* — CSP-B re-acquires a token and retries; no unbounded retry storm                                                                                                       | SEC  |                                                                                                                |
| OSP-24 | Full flows of both origins observed end to end | Record **which** transitions produce callbacks, and what `bpnl` value is sent at statuses where no BPNL has been assigned yet (`CREATED`, `INVITE_USER`, `SUBMITTED`, early `DECLINED`) | GAP  | Emission points are undefined, and mandatory `bpnl` is unsatisfiable pre-assignment (§10.5)                    |

---

## 9. Suite E2E — end-to-end flows

| ID     | Scenario                                                                                                                                                                     | Expected                                                                                                                                                                                                              | Tag  | Notes                                            |
|--------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------|----------------------------------------------------|
| E2E-01 | Legacy happy path (§2.2.1): POST callback config → partnerregistration → complete the process on the CSP-B side                                                              | Each step succeeds with its declared code; callbacks arrive carrying the original `externalId` and `bpnl`, in an order consistent with the legacy state machine, culminating in `CONFIRMED`                            | SPEC |                                                  |
| E2E-02 | Legacy decline path: as E2E-01, but the registration is declined on the CSP-B side                                                                                           | `DECLINED` callback with a non-null, human-readable `message`                                                                                                                                                         | SPEC |                                                  |
| E2E-03 | Legacy auto-submit path: complete §2.2.1 registration with `autoSubmit: true`                                                                                                | Registration reaches `SUBMITTED` without any further OSP interaction after the initial POST                                                                                                                           | SPEC | `autoSubmit` declared in the schema only (§10.3) |
| E2E-04 | Tenant happy path (§2.2.2): POST callback config → fileupload (`externalId`) → PUT content → tenant-registration with all three consent kinds, `fileIds` referencing uploads | 201; callbacks culminate in `CONFIRMED` carrying `bpnl` (and `bpna`/`bpns` if assigned)                                                                                                                               | SPEC |                                                  |
| E2E-05 | Tenant decline path: as E2E-04, but the registration is declined                                                                                                             | `DECLINED` callback with a non-null `message`                                                                                                                                                                         | SPEC |                                                  |
| E2E-06 | Tenant registration without files (three consent kinds, empty `fileIds`) through to a terminal status                                                                        | Flow completes; file uploads are genuinely optional, consents are not                                                                                                                                                 | SPEC |                                                  |
| E2E-07 | Two interleaved registrations (distinct `externalId`s, one per origin)                                                                                                       | Callbacks correlate to the correct `externalId` with a legal `applicationStatus` for each origin; no cross-talk                                                                                                       | SEC  |                                                  |

---

## 10. Spec observations

Findings from deriving this plan against the current spec revision; each is referenced from the cases above.

Resolved since previous revisions (kept for traceability): `PartnerRegistrationData` now declares the nine-field
`required` set, and its stray `agreements`/`fileIds` (plus `AgreementConsentData`/`ConsentStatusId` and the orphaned
`DocumentTypeId`) are gone; the `consents` contract is fully specified and machine-enforced (Mandatory, three required
kinds, `Consent.kind` required); the callback auth is normatively declared (OAuth2 client-credentials, bearer, scope
`callback`); a single callback schema replaces the two divergent ones, removing the tolerance asymmetry; the sequence
diagram and enums agree on `DECLINED`; the `applicationStatus` union is `anyOf`, fixing the formally broken `oneOf`;
`required` sets were added to the callback-config request, `UserDetailData`, and `CompanyUniqueIdData`; `requestBody`
is marked required on §2.2.1 and the callback POST; the callback GET declares "Empty if not configured".

Implementation note (cx-ve): the onboarding-api deliberately deviates in one place — the §2.2.1 `200` returns the
onboarding-process id as its body (the spec declares an empty response; the id is what OSP clients correlate on, and
the tolerant reader permits it). The optional §2.2.3 file upload endpoint is not offered (conformant: this CSP-B
requires no files); `consents[].fileIds` are accepted but nothing dereferences them.

It additionally implements EXTENSIONS beyond the spec — mitigating, implementation-side, the gaps behind findings
§10.1/§10.5/§10.7, which remain open against the spec itself:
client-scoped `GET`/`DELETE /api/administration/osp/v2/tenant-registration[/{externalId}]` — the recovery read for a
lost callback (in-flight states read as `SUBMITTED`; a cancelled registration reads as the extension status
`CANCELLED`, never sent on a callback) and OSP-initiated cancellation (204/404/409, no callback, terminal event
still published); fine-grained per-endpoint scopes (`registration:read`/`registration:write`,
`callback-config:read`/`callback-config:write`) accepted alongside the spec's `configure_partner_registration`
umbrella role; and `bpnl` treated as effectively optional on callbacks (omitted when no BPN exists yet).

Still open or newly introduced:

1. **No read path, anywhere.** Neither flow has a status GET; the §2.2.2 `201` carries no body and no `Location`. A
   lost callback is unrecoverable, and the OSP cannot poll (TEN-01, OSP-22).
2. **Asymmetric error contracts.** §2.2.1 still declares only `200/401/500` — no `400` despite nine mandatory fields
   and no `409` despite the same `externalId` uniqueness §2.2.2 gets a `409` for; the OSP callback declares no
   `400`/`404` despite three required fields (REG-10, REG-40, OSP-08..10).
3. **Remaining markdown ↔ OpenAPI misalignments** despite "MUST remain aligned" (§2.1.1): `did`/`autoSubmit` exist
   only in the `PartnerRegistrationData` schema, not the §2.2.1 field table; the GET-400 descriptions differ
   ("invalid input" vs. "not an Onboarding Service Provider"); the §2.3.1 field table still says `oneOf(…)` while the
   normative schema now — correctly — uses `anyOf` (REG-04..05, CBC-12).
4. **No status discriminator.** The `applicationStatus` union is `anyOf` now (the broken `oneOf` is fixed), but the
   enums fully overlap on `SUBMITTED`/`CONFIRMED`/`DECLINED`, so distinguishing origin rests entirely on
   receiver-side `externalId` tracking; a misrouted `CREATED`/`INVITE_USER` for a tenant registration remains the
   only schema-detectable violation (OSP-15).
5. **`bpnl` is Mandatory on every callback** — including statuses where no BPNL has been assigned yet (`CREATED`,
   `INVITE_USER`, `SUBMITTED`, early `DECLINED`); the CX-0010 reference does not say when a BPNL exists. Also: which
   transitions emit callbacks remains undefined — including whether the tenant flow emits `SUBMITTED`, which the 201
   already told the OSP; receiver enforcement duties for the state machines are unstated; `RegistrationStatus` values
   still have blank definition cells (OSP-04, OSP-08, OSP-24).
6. **The blanket flip to `additionalProperties: true`.** Nearly every schema flipped from strict to tolerant in this
   revision, undocumented. Tolerant-reader is a defensible policy, but it now silently swallows meaningful mistakes —
   e.g. `companyRoles` sent to the tenant endpoint is ignored without error (TEN-06); the intended
   strictness policy should be stated, and `CompanyUniqueIdData` remaining `false` looks accidental (REG-14, FILE-04,
   CBC-10, OSP-07).
7. **Authentication remainder.** The CSP-B Bearer scheme is still modeled as `type: apiKey`; how
   `configure_partner_registration` is conveyed is undefined; 401 vs 403 for missing role is undeclared; the callback
   security requirement references scope `callback`, but the declared `clientCredentials` flow has no `scopes` map
   (an OpenAPI validity error). Additionally, the flow's `tokenUrl` is the literal placeholder `"{authUrl}"` — server
   variables are not substituted into security schemes — and the `authUrl` server variable is declared without
   appearing in the server URL template, another validity nit. What the OSP must actually validate on inbound tokens
   (issuer? audience? that scope?) is still unstated in prose (AUTH-06, OSP-11).
8. **Tenant scoping.** Nothing binds the callback configuration or fileupload `externalId`s to the authenticated OSP;
   the callback config reads like a global singleton (CBC-14, FILE-15).
9. **Callback-config lifecycle.** No DELETE — a configuration can never be removed, only replaced; secret
   storage/rotation requirements are absent. (The unset-GET case is now declared — "Empty if not configured" — though
   the exact shape of "empty" is not, CBC-11.)
10. **File upload constraints.** The MUST/MAY conditionality (and "when required by the CSP-B configuration" for
    `did`) is undiscoverable by clients; `expiresAt` is Optional although the prose asserts URLs expire; no size
    limits or MIME allow-list; the storage-provider PUT headers are out-of-band, making that leg untestable from the
    spec alone; "all files MUST be uploaded before submitting" is unenforceable since nothing declares how many files
    are expected (FILE suite, REG-41, TEN-14).
11. **Delivery and transport.** Callback retry/at-least-once semantics are undefined (making receiver idempotency
    mandatory in practice but unstated); TLS is never a MUST in either direction; `callbackUrl`/`authUrl` are used
    for server-side requests with no SSRF validation requirements (OSP-13, OSP-22, CBC-09, CBC-13).
12. **Naming and versioning.** `/osp/v2/…` vs unversioned `/api/administration/…` paths; `tenant-registration`
    (kebab) vs `partnerregistration` (concatenated); "the root path can differ" undermines interoperability without a
    discovery mechanism.
13. **Editorial.** "becasue" (spec §2.2.2); an ngrok host in a normative document's example
    (`did:web:teaching-especially-rodent…`); blank definition cells for `INVITE_USER`/`SUBMITTED`/`CONFIRMED`/
    `DECLINED`; the fileupload OpenAPI summary still says "for a partner registration" though the path is under
    `tenant-registration`.
