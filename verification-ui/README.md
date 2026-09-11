# CX-VE Verification UI

An Angular dashboard plus the backend-for-frontend (BFF) it talks to. It makes the VE's
participant journey operable interactively:

1. **Onboard a participant under test** through the Membership Hub (`POST /hub/api/members`).
2. **Run the CX-0135 v3.0.0 Flow B certificate exchange** between the permanent *verification
   participant* (certificate consumer / DSP side varies per flow) and the freshly onboarded
   participant (certificate provider): seed the CCM offers, establish both data flows via the
   EDC management API (catalog → negotiation → `FINALIZED` → transfer → `STARTED`), publish,
   retrieve (byte-compared), accept.
3. **Judge the run against the event ledger** the compliance tracker writes, read through the
   hub's `/api/eventlog` endpoints: the run is `SUCCEEDED` only if every step passed AND every
   expected event subject is present (the checklist is configuration —
   `verification.expected-events`).

Unlike the e2e suite (which onboards both sides fresh per run), the verification participant is
**permanent**: created on first use under a fixed BPN (`BPNLVERIFY000001`), rediscovered from
the hub after restarts, its CCM inbox offer re-seeded idempotently. Runs themselves are held in
memory — a restart forgets them; the participant and the event ledger are durable.

The browser only ever talks to this app (same-origin `/ui`); all platform tokens (RFC 8693
workload-token exchange at jwtlet, under `issuer`/`admin` for the management API and
`sudo`/`certo-mgmt-api:write` for Certo) stay server-side.

## Layout

```
frontend/   Angular 20 SPA (built with base-href /ui/, served from the jar's static resources)
src/        Spring Boot BFF: ported e2e clients (management, certo, hub), the run state
            machine (CertificateExchangeFlow), the verification-participant service
Dockerfile  node build → gradle bootJar (dist embedded) → layered JRE runtime
```

## Local development

Run the BFF (defaults point at a VE on `http://cxve.localhost`, see
`src/main/resources/application.yaml`):

```bash
./gradlew bootRun
```

For the management/Certo legs the BFF exchanges a workload token; mint one into `./token`
(requires the deployed chart's jwtlet seed, which maps the `verification-ui` SA):

```bash
kubectl create token verification-ui -n edc-v \
  --audience=https://kubernetes.default.svc.cluster.local > token
```

Run the frontend dev server (proxies `/api` to `localhost:8080`):

```bash
cd frontend && npm start     # → http://localhost:4200
```

In-cluster, the app is served at `http://cxve.localhost/ui` (HTTPRoute strips the prefix; the
Angular build's base-href and the relative `api/...` calls ride the same prefix). Swagger UI:
`/ui/swagger` externally, `/swagger` locally.

## Build & deploy

```bash
docker build -t ghcr.io/metaform/cx-ve/verification-ui:latest .   # full image (frontend + BFF)
```

`scripts/install-ve.sh` / `scripts/redeploy-ve.sh` build and load the image and deploy the
`charts/verification-ui` subchart as part of the umbrella release; its jwtlet seed job
(hook weight 240) registers the SA's two token-exchange mappings.

## Tests

```bash
./gradlew test               # context smoke, BPN derivation, checklist, run state machine,
                             # participant permanence (mocked clients)
```

Angular component tests are deliberately not part of v1. The end-to-end proof is manual: open
`http://cxve.localhost/ui`, ensure the verification participant (first time onboards it —
minutes), start a run, watch the 11-step timeline and the checklist fill, expect the VERIFIED
stamp.
