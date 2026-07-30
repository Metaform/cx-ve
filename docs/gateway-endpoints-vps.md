# Gateway endpoints and authentication 

This document outlines how to install the Catena-X Verification Environment on a dedicated Kubernetes cluster that is 
internet-reachable via DNS. For the purposes of this document, we'll use `"vps.yourdomain.com"` as a stand-in.

Core Platform Distribution 0.0.17, rendered with `global.host=vps.yourdomain.com`. One Gateway (`edcv-gateway`), a single
**plain-HTTP listener on port 80** — there is no TLS listener in the chart. Routes fall into three auth classes:

1. **Administration APIs** — Traefik `jwt-auth` forwardAuth middleware (clearglass) validating a jwtlet-issued JWT,
   usually with a second in-app validation of the same token.
2. **Dataspace protocol endpoints (DSP/DCP)** — no gateway middleware *by design*
   (counterparties hold no jwtlet token); authentication is DCP self-issued ID tokens, validated in-app.
3. **Anonymous** — DID documents, the jwtlet token endpoint (self-securing), and the telemetry UIs (see warnings).

<!-- TOC -->
* [Gateway endpoints and authentication](#gateway-endpoints-and-authentication-)
  * [Endpoint overview](#endpoint-overview)
  * [Administration APIs (clearglass at the gateway + in-app validation)](#administration-apis-clearglass-at-the-gateway--in-app-validation)
    * [`/api/management` → EDC Management API (controlplane:8081)](#apimanagement--edc-management-api-controlplane8081)
    * [`/api/identity` → IdentityHub Identity API (identityhub:7081)](#apiidentity--identityhub-identity-api-identityhub7081)
    * [`/api/issuer/admin` → IssuerService Admin API (issuerservice:10013)](#apiissueradmin--issuerservice-admin-api-issuerservice10013)
    * [`/api/tm`, `/api/pm` → CFM Tenant Manager / Provision Manager (:8080)](#apitm-apipm--cfm-tenant-manager--provision-manager-8080)
    * [`/api/siglet` → siglet token API (siglet:8080, prefix stripped)](#apisiglet--siglet-token-api-siglet8080-prefix-stripped)
  * [Dataspace protocol endpoints (DSP/DCP — in-app auth, deliberately no middleware)](#dataspace-protocol-endpoints-dspdcp--in-app-auth-deliberately-no-middleware)
    * [`/api/dsp` → DSP protocol (controlplane:8082, path NOT rewritten)](#apidsp--dsp-protocol-controlplane8082-path-not-rewritten)
    * [`/api/credentials` → DCP CredentialService (identityhub:7082, not rewritten)](#apicredentials--dcp-credentialservice-identityhub7082-not-rewritten)
    * [`/api/issuance` → DCP Issuance API (issuerservice:10012, not rewritten)](#apiissuance--dcp-issuance-api-issuerservice10012-not-rewritten)
  * [Public endpoints](#public-endpoints)
    * [DID documents (anonymous, HTTP GET)](#did-documents-anonymous-http-get)
    * [Token exchange at `/api/auth` → jwtlet (see token-exchange section)](#token-exchange-at-apiauth--jwtlet-see-token-exchange-section)
  * [The operator-token-exchange machinery (clearglass + jwtlet)](#the-operator-token-exchange-machinery-clearglass--jwtlet)
    * [jwtlet — `POST http://vps.yourdomain.com/api/auth/token`](#jwtlet--post-httpvpsyourdomaincomapiauthtoken)
    * [clearglass — the `jwt-auth` middleware](#clearglass--the-jwt-auth-middleware)
  * [⚠ Telemetry routes — review before going public](#-telemetry-routes--review-before-going-public)
  * [Auth/exposure flags (defaults all `true` unless noted)](#authexposure-flags-defaults-all-true-unless-noted)
  * [Cross-cutting security posture on the VPS](#cross-cutting-security-posture-on-the-vps)
<!-- TOC -->

## Endpoint overview

| External URL (prefix)                           | Backend (rewritten path)                  | Gateway auth                                     | In-app auth                                      |
|-------------------------------------------------|-------------------------------------------|--------------------------------------------------|--------------------------------------------------|
| `http://vps.yourdomain.com/api/management`       | controlplane:8081 (`/api/mgmt`)           | clearglass: `management-api:*`                   | jwtlet JWT (OAuth2 filter) + per-endpoint scopes |
| `http://vps.yourdomain.com/api/identity`         | identityhub:7081 (`/api/identity/v1beta`) | clearglass: `identity-api:*`                     | jwtlet JWT + per-endpoint scopes                 |
| `http://vps.yourdomain.com/api/issuer/admin`     | issuerservice:10013 (`/api/admin/v1beta`) | clearglass: `issuer-admin-api:*`                 | jwtlet JWT (scope granularity gateway-only)      |
| `http://vps.yourdomain.com/api/tm`               | tenant-manager:8080 (`/api/v1alpha1`)     | clearglass: `tenant-manager-api:*`               | jwtlet JWT + exact-match scopes                  |
| `http://vps.yourdomain.com/api/pm`               | provision-manager:8080 (`/api/v1alpha1`)  | clearglass: `provision-manager-api:*`            | jwtlet JWT + exact-match scopes                  |
| `http://vps.yourdomain.com/api/siglet`           | siglet:8080 (prefix stripped)             | clearglass: `siglet-api:*` / `siglet-mgmt-api:*` | **none** — clearglass is the only gate           |
| `http://vps.yourdomain.com/api/dsp`              | controlplane:8082 (unrewritten)           | none                                             | DCP self-issued ID token + VP exchange           |
| `http://vps.yourdomain.com/api/credentials`      | identityhub:7082 (unrewritten)            | none                                             | DCP self-issued ID token (per endpoint)          |
| `http://vps.yourdomain.com/api/issuance`         | issuerservice:10012 (unrewritten)         | none                                             | DCP holder token; holder must be onboarded       |
| `http://identity.vps.yourdomain.com/`            | identityhub:7083                          | none                                             | none — public DID documents                      |
| `http://issuer.vps.yourdomain.com/`              | issuerservice:10016                       | none                                             | none — public DID document                       |
| `http://vps.yourdomain.com/api/auth`             | jwtlet:8080 (prefix stripped)             | none                                             | K8s TokenReview of the subject token             |
| `http://grafana.localhost/` etc. (×4 telemetry) | grafana/jaeger/loki/prometheus            | none                                             | none — see warnings                              |

---



## Administration APIs (clearglass at the gateway + in-app validation)

### `/api/management` → EDC Management API (controlplane:8081)

External shape `http://vps.yourdomain.com/api/management/v5beta/participants/<pcid>/...`
(rewritten to `/api/mgmt/...`). Two independent gates, both on the jwtlet JWT:

1. **Gateway**: clearglass, `management-api:*` scopes per the route map.
2. **In-app** (`management-api-oauth2-authentication` + `-authorization` in the BOM):
   `JwtValidatorFilter` re-validates signature against the jwtlet JWKS **plus `iss` equality**
   (`edc.iam.oauth2.issuer` — a check clearglass does not do), `exp`/`nbf`;
   `ServicePrincipalAuthenticationFilter` then requires the token's `sub` to reference an existing participant context
   *unless* the scope satisfies `management-api:admin`; per-endpoint `@RequiredScope` is enforced in-app as well.

There is **no API-key auth** (`edc.api.auth.*` is absent) — in-cluster callers hitting
`controlplane:8081` directly also need a valid jwtlet JWT.

### `/api/identity` → IdentityHub Identity API (identityhub:7081)

External shape `http://vps.yourdomain.com/api/identity/participants/...` (rewrite inserts
`/v1beta`). Same two-gate pattern: clearglass (`identity-api:*`, incl. resource rules like
`identity-api:dids:read`) + in-app OAuth2 (`identityhub-oauth2-bom` replaces the classic x-api-key auth):
`JwtValidatorFilter` (jwtlet JWKS + `iss` check),
`ServicePrincipalAuthenticationFilter` (non-admin `sub` must be an existing participant context; `identity-api:admin`
elevates), **and** in-app per-endpoint scope enforcement (`ScopeBasedAccessFeature`). The
`web.http.identity.auth.key: "password"` setting in the config is dead — its consumer (`auth-tokenbased`) is not in the
deployed BOM.

### `/api/issuer/admin` → IssuerService Admin API (issuerservice:10013)

External shape `http://vps.yourdomain.com/api/issuer/admin/...` (rewritten to
`/api/admin/v1beta/...`). Gateway: clearglass, `issuer-admin-api:*` scopes. In-app:
`JwtValidatorFilter` + `ServicePrincipalAuthenticationFilter` (as above, admin scope
`issuer-admin-api:admin`), plus resource-ownership checks (non-admin `sub` must own the addressed participant context).
**Asymmetry vs IdentityHub:** the deployed
`issuerservice-oauth2-bom` does *not* include the in-app per-endpoint scope enforcement — the fine-grained scopes
(`issuer-admin-api:holders:write`, …) are enforced **only** by clearglass at the gateway. `edc.ih.api.superuser.key` in
the issuerservice config is vestigial (no consumer in the deployed source). Caveat: claims verified against the
platform-images build; a JAD-built issuerservice image could add extensions that change auth wiring.

### `/api/tm`, `/api/pm` → CFM Tenant Manager / Provision Manager (:8080)

External shape `http://vps.yourdomain.com/api/tm/tenants` etc. (rewritten to
`/api/v1alpha1/...`). Gateway: clearglass (`tenant-manager-api:*` / `provision-manager-api:*`). In-app (Go, chi
middleware, `auth.enabled: true` hardcoded in the config templates): full OIDC validation of the jwtlet JWT — JWKS,
**expected issuer, audience `edcv`** — plus per-route
`RequireScope("tenant-manager-api:read"|"write")` etc. Note the in-app scope check is **exact string match** (no admin ⊇
write ⊇ read implication), so a token carrying only `admin`-role claims fails in-app for TM/PM — use `cfm-read`/
`cfm-write` (or the direct scopes). Their
`/health` endpoints are unauthenticated but not reachable through the gateway (the rewrite maps everything into
`/api/v1alpha1/`).

### `/api/siglet` → siglet token API (siglet:8080, prefix stripped)

Serves `GET/DELETE /tokens/{participantContextId}/{id}`, `POST /tokens/verify`, `GET /keys`
(JWKS), `/health`. **In-app auth on this port is disabled** (`[signaling_auth]
mode="disabled"`) — **clearglass is the only gate**; if the middleware is removed (`edc.siglet.authEnabled=false` or
global flags) the route is fully open, including reading stored dataplane EDR tokens. The route map only allows
`GET /tokens/**` (`siglet-api:read` or
`siglet-mgmt-api:read`) and `/key-mappings/**` — everything else (DELETE, `/tokens/verify`,
`/keys`, `/health`) hits default-deny → 403. Quirk: `/key-mappings` actually lives on the management port 8083 (not
gateway-exposed), so those gateway rules match requests that 404 at the backend. Siglet's refresh API (8082) and
signaling API (8081) are cluster-internal.

---

## Dataspace protocol endpoints (DSP/DCP — in-app auth, deliberately no middleware)

External counterparties authenticate with **DCP self-issued ID tokens**, not jwtlet tokens — attaching clearglass here
would break every counterparty. All verification is in-app.

### `/api/dsp` → DSP protocol (controlplane:8082, path NOT rewritten)

URL shape `http://vps.yourdomain.com/api/dsp/{participantContextId}/{profileId}/...`
(e.g. `.../catalog/request` under profile `http-dsp-profile-2025-1`). The path is passed through unrewritten because the
advertised callback address embeds it (`edc.dsp.callback.address: http://vps.yourdomain.com/api/dsp/%s`) — rewrite would
make advertised and served URLs diverge.

Every DSP message must carry `Authorization: Bearer <self-issued ID token>` (missing → 401):

- signature verified against the **counterparty's DID document** (key = JWT `kid`, which must equal `<iss>#<key-id>`);
- `iss == sub` (self-issued), `aud` must equal the **receiving participant's DID**
  (`did:web:identity.vps.yourdomain.com:<participantContextId>`), `exp`/`nbf`/`iat` (5 s leeway), one-time `jti` (replay
  protection enabled);
- the token's `token` claim (presentation access token) is used to fetch the counterparty's **Verifiable Presentation**
  from their Credential Service; presentations are verified (signatures, VP holder == token `iss`, revocation) and
  credentials checked against the trusted issuer —
  `edc.iam.trusted-issuer.issuer.id: did:web:issuer.vps.yourdomain.com:issuer`;
- the policy engine then evaluates required credentials per request type. Failure at any step → 401.

Anonymous exception: `GET /api/dsp/{participantContextId}/.well-known/dspace-version`.

### `/api/credentials` → DCP CredentialService (identityhub:7082, not rewritten)

Advertised to counterparties as `http://vps.yourdomain.com/api/credentials/v1/participants/<pcid>`. Per-endpoint in-app
auth:

- **`POST .../presentations/query`** — Bearer self-issued ID token: signature via caller DID,
  `aud` = the target participant's DID, `iss==sub`, `jti` one-time, plus an inner `token`
  claim: the access token *this IdentityHub issued earlier* (validated against the participant's own key; its `scope`
  claim bounds which credentials may be queried). Bad token → 401; query exceeding granted scope → 403.
- **`POST .../credentials` (storage)** and **`POST .../offers`** — Bearer self-issued token from the **issuer**,
  verified via the issuer's DID document, `aud` = participant DID. Note: this layer checks signature/shape but **not**
  issuer trust (explicit TODO in the source) — any resolvable DID passes this filter; trust is applied downstream.

### `/api/issuance` → DCP Issuance API (issuerservice:10012, not rewritten)

- **`POST /v1beta/participants/issuer/credentials`** (credential request) — Bearer self-issued token from the
  **holder**; `iss==sub`, `aud` = the issuer's DID (`did:web:issuer.vps.yourdomain.com:issuer`), signature via the
  holder's DID document, one-time `jti`; **the holder's DID must already be onboarded** (`HolderStore` lookup; anonymous
  holders disabled by default) → else 401.
- **`GET /v1beta/participants/issuer/requests/{id}`** — same, plus results filtered to the authenticated holder.
- **`GET /v1beta/participants/issuer/metadata`** — anonymous by design (issuer metadata).

---

## Public endpoints

### DID documents (anonymous, HTTP GET)

- `http://identity.vps.yourdomain.com/<participantContextId>/did.json` →
  `did:web:identity.vps.yourdomain.com:<participantContextId>` (also `/<pcid>` and
  `/<pcid>/.well-known/did.json`).
- `http://issuer.vps.yourdomain.com/issuer/did.json` →
  `did:web:issuer.vps.yourdomain.com:issuer`.

Served unrewritten from dedicated hostnames (the URL↔DID mapping is mechanical and exact-match). Only `PUBLISHED` DIDs
are served; unknown DID → empty 2xx (204). No auth of any kind — DID documents are public key material.

### Token exchange at `/api/auth` → jwtlet (see token-exchange section)

`POST /token` (self-securing via TokenReview), `GET /.well-known/jwks.json`,
`GET /.well-known/oauth-authorization-server`, `GET /health` — all anonymous at the gateway; only the
JWKS/metadata/health are *usable* anonymously.

---

## The operator-token-exchange machinery (clearglass + jwtlet)

### jwtlet — `POST http://vps.yourdomain.com/api/auth/token`

Access control of the Administration APIs is based solely on RFC 8693 token exchange
(`grant type = urn:ietf:params:oauth:grant-type:token-exchange`). There are no user accounts, no passwords, no client
secrets anywhere in the platform. The credential is the `subject_token`: it must be a **Kubernetes ServiceAccount token
of this cluster** with audience `https://kubernetes.default.svc.cluster.local`, verified via the K8s TokenReview API.
The caller's identity (`system:serviceaccount:<ns>:<sa>`) must have a **mapping** to the requested `resource`
(participant context), and requested `scope`s must be a subset of the mapping's scopes. `subject_token_type` and
`client_id` are accepted but ignored. The Issued JWT has the following properties:

- `sub=<participantContextId>`
- `iss=http://jwtlet.edc-v.svc.cluster.local:8080`
- `aud=edcv`
- 1 h lifetime, signed via Vault transit; public keys at `http://vps.yourdomain.com/api/auth/.well-known/jwks.json`
  (public by design, as is `/.well-known/oauth-authorization-server` and `/health`).

**Net effect: the token endpoint is anonymous at the gateway but unusable without an in-cluster SA token — external
callers cannot mint tokens. Every Administration API client needs a service account at cluster level.**

Pre-configured mappings:

| K8s ServiceAccount    | `resource` | scopes                                                                           |
|-----------------------|------------|----------------------------------------------------------------------------------|
| `edc-v:seed-jobs`     | `issuer`   | read, write, admin, cfm-read, cfm-write                                          |
| `edc-v:redline`       | `redline`  | read, write, admin, cfm-read, cfm-write, provision/tenant-manager-api:read/write |
| `edc-v:issuerservice` | `issuer`   | read                                                                             |
| `edc-v:siglet-sa`     | `siglet`   | read                                                                             |

Role scopes expand into the token's `scope` claim:

| Requested scope                                   | Expands to claim                                                              |
|---------------------------------------------------|-------------------------------------------------------------------------------|
| `read`                                            | `identity-api:read management-api:read issuer-admin-api:read siglet-api:read` |
| `write`                                           | `identity-api:write management-api:write issuer-admin-api:write`              |
| `admin`                                           | `management-api:admin identity-api:admin issuer-admin-api:admin`              |
| `cfm-read`                                        | `provision-manager-api:read tenant-manager-api:read`                          |
| `cfm-write`                                       | `provision-manager-api:write tenant-manager-api:write`                        |
| `siglet-read` / `siglet-write`                    | `siglet-mgmt-api:read` / `siglet-mgmt-api:write`                              |
| fine-grained (e.g. `management-api:assets:write`) | itself, 1:1                                                                   |

**Getting an operator token** (requires kubectl access to the cluster — that *is* the operator credential). To access
any of the Administration APIs the following commands are necesary

```bash
# get service account token
SA_TOKEN=$(kubectl create token seed-jobs -n edc-v \
  --audience=https://kubernetes.default.svc.cluster.local)

# exchange SA token for scoped token
ACCESS_TOKEN=$(curl -s -X POST http://vps.yourdomain.com/api/auth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "grant_type=urn:ietf:params:oauth:grant-type:token-exchange" \
  --data-urlencode "subject_token=${SA_TOKEN}" \
  --data-urlencode "resource=seed-jobs" \
  --data-urlencode "scope=admin cfm-read cfm-write" | jq -r .access_token)

# call one of the Administration APIs
curl -H "Authorization: Bearer ${ACCESS_TOKEN}" http://vps.yourdomain.com/api/management/...
```

### clearglass — the `jwt-auth` middleware

As an additional layer of defense, Traefik employs a custom auth plugin called `clearglass` to pre-authorize requests
based on the scope they carry. Per request, it validates:

- `Authorization: Bearer <JWT>` present (else **401**), JWT header must carry `kid`;
- signature against **jwtlet's JWKS** (key looked up by `kid`, JWKS cached);
- `exp` and `nbf` (10 s leeway);
- **`iss` and `aud` are NOT validated** — trust is anchored purely in the signature;
- the **route map** (`default: deny`, first-match-wins) against `X-Forwarded-Method` +
  `X-Forwarded-Uri`. The URI carries the **rewritten backend path** (`/api/mgmt/**`,
  `/api/v1alpha1/**`, `/api/admin/**` …) because forwardAuth runs after URLRewrite — empirically observed (report-only
  logs), not verified in Traefik source; if the order were ever reversed the default-deny map would fail closed (403),
  not open.
- Scope grammar: token `scope` claim must satisfy the matched rule's `anyOf`, with implication
  `admin ⊇ write ⊇ read` and api-level (`identity-api:read`) or wildcard (`identity-api:*:read`) covering resource-level
  requirements. Insufficient scope / no matching rule → **403**.

On success the original request — including the Bearer token — is forwarded unchanged, which is what enables the second,
in-app validation of the same token.

To obtain the route permissions, simply inspect clearglass' configuration with:

```bash
kubectl describe configmap -n edc-v clearglass-routes
```

this yields entries like:

```yaml
 - anyOf:
     - management-api:transfers:read
   methods:
     - GET
   path: /api/mgmt/*/participants/*/transferprocesses/**
```

which means "for any GET request to /api/mgmt/ */participants/*/transferprocesses/** your token need at least
`scope=management-api:transfers:read`"

---

## ⚠ Telemetry routes — review before going public

`grafana.localhost`, `jaeger.localhost`, `loki.localhost`, `prometheus.localhost` are **hardcoded literal hostnames**
(not derived from `global.host`) with **no auth middleware under any configuration**, and:

- **Host-header matching, not DNS, decides routing**: anyone who reaches the VPS's port 80 can hit them with
  `curl -H "Host: prometheus.localhost" http://vps.yourdomain.com/`. They are effectively public on the VPS.
- **Grafana** runs with anonymous auth = org role **Admin** (currently unreachable through the gateway only because the
  chart defines no `core-platform-grafana` Service — a chart bug).
- **Loki** (`auth_enabled: false`) and **Prometheus** (`--web.enable-otlp-receiver`) accept unauthenticated **writes**
  (log/metric injection), not just reads.
- **Jaeger** UI has no auth.

On the VPS, set `telemetry.jaeger.enabled=false`, `...prometheus...`, `...loki...`,
`...grafana...=false` (or put real, firewalled hostnames + auth in front) before exposing port 80 publicly.

---

## Auth/exposure flags (defaults all `true` unless noted)

| Flag                                                                                                                                                                             | Effect                                                                                       |
|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------|
| `global.authEnabled`                                                                                                                                                             | Master switch — `false` strips `jwt-auth` from **every** route                               |
| `security.clearglass.enabled`                                                                                                                                                    | `false` removes clearglass + the Middleware (routes stay up, unauthenticated at the gateway) |
| `edc.{controlplane,identityhub,issuerservice,siglet}.authEnabled`, `cfm.{provisionManager,tenantManager}.authEnabled`                                                            | Per-route middleware attach                                                                  |
| `security.clearglass.routeMap.enforce` / `.default`                                                                                                                              | `true` / `deny` — report-only mode if disabled                                               |
| `edc.controlplane.protocol.exposed`, `edc.identityhub.credentials.exposed`, `edc.identityhub.did.exposed`, `edc.issuerservice.issuance.exposed`, `edc.issuerservice.did.exposed` | Remove the corresponding unauthenticated route(s)                                            |
| `telemetry.<component>.enabled`                                                                                                                                                  | Remove the component incl. its route                                                         |
| `security.gateway.enabled`, `security.gatewayClass.enabled`                                                                                                                      | Remove the Gateway / GatewayClass                                                            |
| `security.jwtlet.enabled`                                                                                                                                                        | Remove jwtlet incl. `/api/auth`                                                              |

If middleware is dropped: TM/PM stay protected (in-app auth hardcoded on), management/ identity/issuer-admin stay
protected (in-app OAuth2), **siglet becomes fully open**.

---

## Cross-cutting security posture on the VPS

1. **Everything is plain HTTP** — the Gateway has no TLS listener, `global.external.scheme` is
   `http`, and all advertised URLs/DIDs use it. Every bearer token (jwtlet operator tokens, DCP self-issued tokens,
   `/api/auth` responses) crosses the public internet in cleartext and is capturable/replayable. TLS termination in
   front of Traefik + `scheme: https` is the single most important hardening step for a public deployment.
2. clearglass validates **signature + expiry only** (no `iss`/`aud`) — acceptable because the JWKS is pinned to jwtlet,
   but the in-app checks (which do validate `iss`, and for TM/PM
   `aud`) are the stricter layer.
3. Defense-in-depth varies by API: management/identity = two full gates; issuer-admin = two gates but fine-grained
   scopes only at the gateway; TM/PM = two gates (exact-match scopes in-app); siglet = gateway only; DSP/DCP = in-app
   only (by protocol design).
