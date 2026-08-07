# cx-ve

Monorepo containing:

| Path | Contents |
|---|---|
| `onboarding-api/` | Spring Boot application (Java 17, Gradle) — self-contained build |
| `charts/onboarding-api/` | Helm chart for the Onboarding API application |
| `charts/cx-ve/` | Umbrella chart: the whole VE (platform, Catena-X profile, Onboarding API, Certo + agent) as one release |
| `scripts/` | Utility and automation scripts |

## Building

The Gradle build lives in `onboarding-api/` (its own build root — the repository root is not a
Gradle project):

```shell
cd onboarding-api
./gradlew build            # compile and run tests
./gradlew bootRun          # run the application locally (port 8080)
./gradlew bootBuildImage   # build an OCI container image via buildpacks
docker build -t cx-ve .    # alternative image build (layered jar Dockerfile)
```

## Deploying

The whole VE deploys as ONE umbrella release. The release name `cx-ve` is load-bearing — the
platform names its infra resources `<release>-nats` / `<release>-vault` / `<release>-postgresql`
and the checked-in values reference those names:

```shell
helm dependency update charts/cx-ve
helm lint charts/onboarding-api charts/cx-ve
helm install cx-ve charts/cx-ve -n edc-v --create-namespace
```

The umbrella pulls the Core Platform Distribution, the Catena-X profile and Certo as OCI
dependencies and vendors the local `charts/onboarding-api`. All seeding runs as post-install
hooks of the single release, in one ordered hook space: platform seeds (weights 10/20) →
Catena-X profile (110-130) → onboarding-api jwtlet mapping (200) → certo jwtlet mappings (210)
→ certo activity/orchestration (220).

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
4. Installs the **umbrella release** `cx-ve` into namespace `edc-v` (dependencies resolved via
   `helm dependency update`; all images are pulled from their registries — nothing is built or
   kind-loaded).
5. Re-runs `setup-did-dns.sh` in discovery mode: rewrites are re-derived from the deployed
   HTTPRoutes and verified end to end (in-cluster DNS + the issuer DID document).

Once complete, the Onboarding API is reachable through the gateway at
`http://cxve.localhost/onboarding` (Swagger UI at `/onboarding/swagger`). Register a test
participant against it with

```shell
./scripts/onboard-participant.sh --name "My Company GmbH"
```

which submits a partner registration and follows the onboarding progress in the application
logs (requires `curl` and `jq`).

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

Participants get their data plane registered at onboarding time: the Onboarding API attaches
the configured transfer-type mapping (`participant.dataplane.*`) to the `cfm.dataplane` VPA of
the participant profile, and the platform's siglet agent installs it in Siglet and registers
the data-plane instance with the control plane.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
