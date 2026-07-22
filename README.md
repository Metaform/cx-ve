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

1. **Recreates** the kind cluster `cxve` (an existing cluster of that name is deleted first).
   The kubeconfig is written to `~/.kube/cxve.config`; `kind-config.yaml` maps host ports 80/443
   into the node, so the gateway is reachable on `http://localhost` without a port-forward.
2. Installs Traefik (`traefik-values.yaml`) and the Gateway API CRDs.
3. Installs the **Core Platform Distribution** as release `core-platform` into namespace `edc-v`,
   with `platform-override-values.yaml` as values. The release name matters: the app's chart
   values reference resources derived from it (`core-platform-nats`, `core-platform-vault`,
   `core-platform-nats-auth`).
4. Installs the **Catena-X profile** (dataspace-specific seeding) as release `cx-profile`.
5. Builds the Onboarding API image from source, loads it into the kind cluster and installs the
   app chart as release `obapi`.

Once complete, the Onboarding API is reachable through the gateway at
`http://cxve.localhost/onboarding` (Swagger UI at `/onboarding/swagger`).

The charts default to the published OCI versions pinned in the script; override the chart
sources via the environment variables `CORE_CHART`, `CXPROF_CHART` and `OBAPI_CHART`, e.g. to
install the app chart from the working tree:

```shell
OBAPI_CHART=charts/cx-ve ./scripts/install-ve.sh
```

The cluster is left running when the script finishes; remove it with
`kind delete cluster -n cxve`.

## License

Apache License 2.0 — see [LICENSE](LICENSE).
