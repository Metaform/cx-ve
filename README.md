# cx-ve

Monorepo containing:

| Path | Contents |
|---|---|
| `app/` | Spring Boot application (Java 17, Gradle) |
| `charts/cx-ve/` | Helm chart for deploying the application |
| `scripts/` | Utility and automation scripts |

## Building

```shell
./gradlew build          # compile and run tests
./gradlew :app:bootRun   # run the application locally (port 8080)
./gradlew :app:bootBuildImage   # build an OCI container image via buildpacks
```

## Deploying

```shell
helm lint charts/cx-ve
helm install cx-ve charts/cx-ve
```

## Local deployment (kind)

`scripts/install-ve.sh` stands up a complete local environment — platform, Catena-X profile and
Onboarding API — on a [kind](https://kind.sigs.k8s.io) cluster. Run it from the repository root:

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
3. Installs the **Core Platform Distribution** as release `core-platform` into namespace `edc-v`,
   with `platform-override-values.yaml` as values. The release name matters: the app's chart
   values reference resources derived from it (`core-platform-nats`, `core-platform-vault`,
   `core-platform-nats-auth`).
4. Installs the **Catena-X profile** (dataspace-specific seeding) as release `cx-profile`.
5. Builds the Onboarding API image from source, loads it into the kind cluster and installs the
   app chart as release `obapi`.

Once complete, the Onboarding API is reachable through the gateway at
`http://cxve.localhost/onboarding` (Swagger UI at `/onboarding/swagger`). Register a test
participant against it with

```shell
./scripts/onboard-participant.sh --name "My Company GmbH"
```

which submits a partner registration and follows the onboarding progress in the application
logs (requires `curl` and `jq`).

The charts default to the published OCI versions pinned in the script; override the chart
sources via the environment variables `CORE_CHART`, `CXPROF_CHART` and `OBAPI_CHART`, e.g. to
install the app chart from the working tree:

```shell
OBAPI_CHART=charts/cx-ve ./scripts/install-ve.sh
```

The cluster is left running when the script finishes; remove it with
`kind delete cluster -n cxve`.

### Running two VEs side by side

Two (or more) VEs can coexist on one host, each in its own kind cluster. Uniqueness comes from
the **cluster DNS domain** (`--dns-domain`), which is embedded in every participant and issuer
DID (`did:web:…edc-v.svc.<dns-domain>…`) — the namespace itself must stay `edc-v` because the
platform's CFM agents hardcode it in the token-exchange client ids they register. Give the
second VE distinct host ports and, to allow cross-cluster routing later, distinct pod/service
subnets:

```shell
OBAPI_CHART=charts/cx-ve ./scripts/install-ve.sh -c ve1 -d ve1.local -H ve1.localhost

OBAPI_CHART=charts/cx-ve ./scripts/install-ve.sh -c ve2 -d ve2.local -H ve2.localhost \
  --http-port 8081 --https-port 8444 \
  --pod-subnet 10.245.0.0/16 --service-subnet 10.97.0.0/16
```

Onboard a participant in each:

```shell
./scripts/onboard-participant.sh --name "Alpha Manufacturing" --cluster ve1 \
  --namespace edc-v --api-url http://ve1.localhost/onboarding
./scripts/onboard-participant.sh --name "Beta Logistics" --cluster ve2 \
  --namespace edc-v --api-url http://ve2.localhost:8081/onboarding
```

Then connect the two VEs so their participants can resolve each other's DIDs and verify each
other's credentials (static routes between the kind nodes, CoreDNS zone forwarding of the peer
DNS domain, and each VE trusting the peer's issuer):

```shell
./scripts/connect-ves.sh
```

The script's defaults match the install commands above and it verifies itself by fetching the
peer issuer's DID document from inside each cluster. The routes do not survive a restart of
the kind node containers — re-run the script after a docker restart.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
