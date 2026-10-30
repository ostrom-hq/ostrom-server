# ostrom-server

The Ostrom server, in Kotlin/Native (no JVM): one static-ish binary for Linux x64 and arm64, Ktor on the CIO engine,
PostgreSQL, Koin, Clean Architecture with one Gradle module per functional domain.

## Licensing

Ostrom server follows the open-core model of GitLab and PostHog:

- everything in this repository is licensed under the **GNU Affero General Public License v3.0** ([`LICENSE`](LICENSE)),
  **except** the [`ee/`](ee/) directory;
- [`ee/`](ee/) holds the enterprise features, under a **commercial license** ([`ee/LICENSE`](ee/LICENSE)): you may read,
  modify and test that code freely, but running it in production requires an Ostrom Enterprise license key.

The AGPL means that if you run a modified version of the server for users over a network, you must offer them the
source of your modified version. Code outside `ee/` never depends on code inside `ee/`, so a build without `ee/` is a
complete AGPL server. `ostrom-core` and `ostrom-cli` are Apache 2.0.

## Layout

| Path | Role |
|---|---|
| `kernel/` | Configuration, structured logs, PostgreSQL and migrations, uniform HTTP errors, token authentication, the instance signing key, the `ServerModule` contract |
| `modules/<name>/` | One functional domain each (`identity`, `orgs`, `receipts`…), with `domain/`, `infrastructure/`, `presentation/` packages |
| `testing/` | Test helpers: a throwaway PostgreSQL database per test, a test server |
| `app/` | The binary: wires the kernel and every module, applies migrations, serves HTTP |
| `ee/` | Enterprise modules (commercial license) |
| `build-logic/` | The `ostrom.native-module` convention plugin (targets, serialization, Koin) |

Adding a module: [`docs/MODULES.md`](docs/MODULES.md).

## Build and run

Builds run in GitHub Actions (Linux): tests on linuxX64 against a PostgreSQL service, binaries for linuxX64 and
linuxArm64, a distroless Docker image and a smoke test of it. Locally (macOS arm64):

```sh
docker compose up -d postgres            # PostgreSQL on localhost:5432
export OSTROM_TEST_DATABASE_URL=postgresql://localhost:5432/postgres
./gradlew macosArm64Test                 # unit and database tests
./gradlew :app:linkDebugExecutableMacosArm64
OSTROM_DATABASE_URL=postgresql://localhost:5432/postgres OSTROM_DATABASE_USER=postgres \
  OSTROM_DATABASE_PASSWORD=postgres ./app/build/bin/macosArm64/debugExecutable/ostrom-server.kexe
curl localhost:8080/health
```

Configuration keys are listed in [`docs/MODULES.md`](docs/MODULES.md#configuration).
