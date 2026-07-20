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

## License

Apache License 2.0 — see [LICENSE](LICENSE).
