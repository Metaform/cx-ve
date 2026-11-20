# Scripts

Utility and automation scripts for the cx-ve project. Each script documents its own options in a header comment and
supports `--help`.

## `install-ve.sh`

Stands up the complete Verification Environment on a single kind cluster (default `cxve`, gateway hostname
`cxve.localhost`) as ONE umbrella helm release (`charts/cx-ve`, release name `cx-ve`): Core Platform Distribution,
DECADE-X profile, the DECADE-X onboarding API, Membership Hub, Verification UI, Certo and the
Certo agent. The apps of this repo (DECADE-X onboarding API, Membership Hub, Verification UI, Compliance Tracker, Certo agent)
are built from source and kind-loaded, so the VE runs the local code. Runs `setup-did-dns.sh --pre` before the release (its seed hooks need in-cluster DNS mid-install) and
again in discovery mode after it.

## `redeploy-ve.sh`

The edit-build-verify loop on an EXISTING cluster: rebuilds the app images from this checkout, kind-loads
them, re-vendors the umbrella's chart dependencies (the local app charts propagate via `file://`) and runs `helm upgrade` with the same host overrides `install-ve.sh` applies — then
explicitly restarts the deployments running the locally built `:latest` images (a rebuilt image under an unchanged
tag is invisible to helm).

The upgrade re-runs the release's seed hooks, which are idempotent, with one exception the script switches off
(`catenax-profile.seedJobs.dataspaceProfile.enabled=false`): the catenax-profile's Tenant Manager seed would
create a second dataspace profile on the cell, which breaks participant provisioning for both dataspaces. The
profile created by the install stays in place. `install-ve-vps.sh` does the same when the release already exists.

## `install-ve-vps.sh`

VPS variant of `install-ve.sh`: installs the same umbrella release on an **existing** cluster reached through a
kubeconfig, with **no DNS magic** — the hostname must be a real, publicly resolvable DNS name pointing at the VPS
(wildcard record recommended: `<host>`, `issuer.<host>` and `identity.<host>` must all resolve), so the CoreDNS
rewrites of `setup-did-dns.sh` are not needed. Nothing is built from source; all images — including the apps
of this repo — are pulled from their registries (the published images from `.github/workflows/publish.yml`). Both
parameters are required:

```bash
./scripts/install-ve-vps.sh -H ve.example.com -k ~/.kube/vps.config
```

After the install it verifies from outside the cluster that the issuer DID document resolves through the gateway.

## `setup-did-dns.sh`

Makes the VE's gateway hostnames resolvable from **inside** the cluster, by adding `rewrite` rules to CoreDNS that
point every HTTPRoute hostname at the Traefik Service, then verifies both DNS and an end-to-end `did:web` resolution.

Needed because `core-platform-distribution` advertises itself under its gateway hostname: the DSP callback
address, the DCP credential-service and issuance endpoints and the `did:web` identifiers all point there
rather than at in-cluster Service FQDNs, and the runtimes dereference those URLs themselves — the control plane
resolves the issuer DID to verify credential signatures and calls the CredentialService on every DSP message. A
`*.localhost` name otherwise resolves to the pod's own loopback and every one of those lookups fails.

```bash
./scripts/setup-did-dns.sh -c cxve                  # apply (from HTTPRoutes) + verify
./scripts/setup-did-dns.sh -c cxve --pre -H <host>  # pre-install: derived hostnames, no DID check
./scripts/setup-did-dns.sh -c cxve --verify-only    # dry-run
```

`install-ve.sh` runs it automatically; recreating the KinD cluster discards the rules, so re-run it after a
reinstall. It is idempotent — the rules live between marker comments in the Corefile and are replaced wholesale.

Everything but the cluster name is discovered from the cluster (DNS domain, Traefik Service, hostname list), so the
rules cannot drift from what is deployed. That matters most for the DNS domain: a rewrite target under a domain
CoreDNS is not authoritative for silently NXDOMAINs.

## `onboard-participant.sh`

Onboards a member into a dataspace — DECADE-X by default, Catena-X with `--dataspace catena-x` (and optionally
`--bpn`; one is derived otherwise) — through the Membership Hub (`POST /hub/api/members` — the full journey: EDC
resource provisioning via the CFM Tenant Manager, then the registration with the dataspace's onboarding API: an
onboarding request approved automatically in DECADE-X, a CX-0009 partner registration in Catena-X) and polls
`GET /hub/api/members/<externalId>` until the membership reaches a terminal state; `CREDENTIALS_OFFERED` is success
(the member is provisioned AND its credentials have been offered to its wallet) and prints the correlated record
incl. the participant context id and the DECADE-X-ID the hub generated. `--short-name` pins the participant's DID
(`did:web:identity.<host>:<short-name>`). No credentials needed — the hub's API is unauthenticated and the hub
itself authenticates to the onboarding API. Submitting onboarding requests directly to the DECADE-X onboarding
API (no provisioning) remains possible with an `osp-client` token.

## `e2e.sh`

End-to-end test: installs the VE, onboards a "Verification Participant" into each dataspace (DECADE-X and
Catena-X), verifies each participant's DID document resolves from **outside** the cluster (plain HTTP from the host
through the gateway) — the way an external dataspace solution will resolve it — and runs the Gradle e2e suite, once
per dataspace (`DecadeXE2eTest`, `CatenaXE2eTest`).

> The dual-VE demo scripts of the first iteration (`connect-ves.sh`, `dsp-tests.sh`, the peer mode of
> `setup-did-dns.sh`) were removed when the repo pivoted to a single Verification Environment; see git history if
> the cross-cluster wiring is ever needed again.
