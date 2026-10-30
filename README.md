# ostrom-server

The Ostrom server, in Kotlin/Native (no JVM at run time): one binary for Linux x64 and arm64, Ktor on the CIO engine,
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
| `app/` | The binary: `migrate` applies every module's migrations, `serve` wires the kernel and every module and serves HTTP until SIGTERM, `seed-test-account` creates a QA account |
| `ee/` | Enterprise modules (commercial license) |
| `build-logic/` | The `ostrom.kmp` convention plugin of ostrom-core's CONVENTIONS (native targets, the test-only `jvm()` target, Kover 100 %, detekt), applied as `ostrom.native-module` |
| `ci/build-linux.sh` | Builds the release linuxX64 binary into `dist/ostrom-server` (image, end-to-end and load tests) |

Adding a module: [`docs/MODULES.md`](docs/MODULES.md).

## Build and run

Builds run in GitHub Actions (Linux, one job `ci`; none on a draft pull request), exactly:

```sh
python3 scripts/check-conventions.py
./gradlew detekt jvmTest koverVerify koverXmlReport linuxX64Test   # with a PostgreSQL service
ci/build-linux.sh                                                   # dist/ostrom-server
./gradlew :app:linkReleaseExecutableLinuxArm64
docker build -t ostrom-server .                                     # distroless image, smoke-tested in CI
```

The JVM target only runs the tests and measures coverage; nothing JVM is shipped. Locally (macOS arm64; on the
shared Mac, only through `.coord/build-lock.sh`):

```sh
docker compose up -d postgres            # PostgreSQL on localhost:5432
export OSTROM_TEST_DATABASE_URL=postgresql://postgres:postgres@localhost:5432/postgres
./gradlew jvmTest koverHtmlReport        # unit and database tests, coverage report
./gradlew :app:linkDebugExecutableMacosArm64
server=./app/build/bin/macosArm64/debugExecutable/ostrom-server.kexe
OSTROM_DATABASE_MIGRATION_URL=postgresql://ostrom_owner:ostrom_owner@localhost:5432/ostrom $server migrate
OSTROM_DATABASE_URL=postgresql://ostrom_app:ostrom_app@localhost:5432/ostrom $server   # never as a superuser
curl localhost:8080/ready
```

Configuration keys and commands are listed in [`docs/MODULES.md`](docs/MODULES.md#configuration); how organizations
are kept apart in PostgreSQL is in [`docs/tenancy.md`](docs/tenancy.md).
