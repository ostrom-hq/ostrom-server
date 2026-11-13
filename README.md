<p align="center">
  <a href="https://ostrom.run">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset=".github/assets/banner-dark.svg">
      <img alt="Ostrom. Agents propose. Your team decides." src=".github/assets/banner-light.svg" width="100%">
    </picture>
  </a>
</p>

<p align="center">
  <strong>Ostrom runs a company's AI coding agents in the cloud and makes them follow the team's rules.</strong><br>
  Every change they make leaves a receipt, and the few that matter wait for a person's yes.
</p>

<p align="center">
  <a href="https://ostrom.run">Website</a> ·
  <a href="https://ostrom.run/docs">Docs</a> ·
  <a href="https://github.com/ostrom-hq/ostrom-server/discussions">Discussions</a> ·
  <a href="CONTRIBUTING.md">Contributing</a> ·
  <a href="SECURITY.md">Security</a>
</p>

<p align="center">
  <a href="https://github.com/ostrom-hq/ostrom-server/actions/workflows/ci.yml"><img alt="CI" src="https://github.com/ostrom-hq/ostrom-server/actions/workflows/ci.yml/badge.svg?branch=main"></a>
  <a href="CONTRIBUTING.md#tests-the-bar"><img alt="Coverage: 100% enforced" src="https://img.shields.io/badge/coverage-100%25%20enforced-8E1B3A"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0%20%2B%20ee-8E1B3A"></a>
  <a href="CHANGELOG.md"><img alt="Version: pre-1.0" src="https://img.shields.io/badge/version-pre--1.0-44393C"></a>
</p>

## What this is

The server is where the rules live and where a person's yes is recorded: it compiles the team's rules into what each
agent session runs, holds the gates that wait for an approver, reads the diff and the checks on GitHub, and signs a
receipt for every change.

The Ostrom server, on the JVM (Java 21, [docs/decisions/0001](docs/decisions/0001-jvm-and-teamcode-stack.md)): one
container image for Linux x64 and arm64 (`ostromhq/ostrom-server` on Docker Hub, built by jib), Ktor on the CIO engine,
PostgreSQL, Koin, Clean Architecture with one Gradle module per functional domain.

## Quick start

```sh
git clone https://github.com/ostrom-hq/ostrom-server.git && cd ostrom-server
docker compose up -d postgres
OSTROM_TEST_DATABASE_URL=postgresql://postgres:postgres@localhost:5432/postgres ./gradlew jvmTest
```

## How it fits

```mermaid
flowchart LR
  dev(["Developer"]) --> cli["ostrom CLI"]
  cli -- "launches, under the rules" --> agent[["Coding agent<br/>Claude Code, Codex, ..."]]
  agent -. "every action, through hooks" .-> cli
  cli -- "sessions, gate requests" --> server["Ostrom server"]
  server --- db[("PostgreSQL")]
  server -- "reads the diff, runs checks" --> gh["GitHub"]
  server -- "a yes is needed" --> person(["Approver"])
  person --> web["Web console"] & app["Mobile app"]
  web & app -- "API" --> server
  server -- "signed receipt" --> receipt[/"Receipt"/]
  core{{"ostrom-core<br/>contract, workflow model, receipts"}} -.- cli & server & web & app
  helm["Helm chart"] -. "deploys" .-> server
  classDef here fill:#8E1B3A,stroke:#8E1B3A,color:#FFFFFF
  class server here
```

## Status

Ostrom is before its first public release.

| Part | Status |
| --- | --- |
| Kernel: configuration, logs, PostgreSQL and migrations, HTTP errors, token authentication, instance signing key | Available |
| Domain modules (identity, orgs, sessions, gates, receipts, runners, billing...) | In progress, one pull request each |
| Container image (amd64, arm64) | Built and smoke-tested in CI, published to Docker Hub from main and tags |

## Layout

| Path | Role |
|---|---|
| `kernel/` | Configuration, structured logs, PostgreSQL and migrations, uniform HTTP errors, token authentication, the instance signing key, the `ServerModule` contract |
| `modules/<name>/` | One functional domain each (`identity`, `orgs`, `receipts`…), with `domain/`, `infrastructure/`, `presentation/` packages |
| `testing/` | Test helpers: a throwaway PostgreSQL database per test, a test server |
| `app/` | The application: `migrate` applies every module's migrations, `serve` wires the kernel and every module and serves HTTP until SIGTERM, then drains, `seed-test-account` creates a QA account, `bootstrap` prints the first owner's setup link |
| `image/` | The container image: the Ktor Gradle plugin's `ktor { docker { } }` and jib package `app/` on `eclipse-temurin:21-jre`, as TeamCode's; `/usr/local/bin/ostrom-server` runs it |
| `ee/` | Enterprise modules (commercial license) |
| `build-logic/` | The `ostrom.kmp` convention plugin of ostrom-core's CONVENTIONS (native targets, the test-only `jvm()` target, Kover 100 %, detekt), applied as `ostrom.native-module` |

Adding a module: [`docs/MODULES.md`](docs/MODULES.md).

## Build and run

Builds run in GitHub Actions (Linux, one job `ci`; none on a draft pull request), exactly:

```sh
python3 scripts/check-conventions.py
./gradlew detekt jvmTest koverVerify koverXmlReport linuxX64Test   # with a PostgreSQL service
./gradlew :image:publishImageToLocalRegistry -PimageTags=ci -PimagePlatforms=amd64   # ostrom-server:ci, smoke-tested
```

The `jvm()` target's tests are the tests of the shipped runtime. `image.yml` pushes `latest` and `sha-<commit>` from
main, `<version>` and `sha-<commit>-<version>` from a release tag, signed with cosign. The version the server reports
(`/health`, `/api/v1/instance`, `/api/v1/capabilities`, its logs and traces) is the Gradle property `ostromVersion`,
written into the kernel's generated `BuildInfo.kt`: CI passes the tag for a release (`image.yml`) and
`0.0.0-edge.<time>.<sha7>` for an image of main; any other build reports `0.0.0-dev` (`./gradlew
-PostromVersion=1.2.3 …` to set it, a semantic version only). Locally (macOS arm64):

```sh
docker compose up -d postgres            # PostgreSQL on localhost:5432
export OSTROM_TEST_DATABASE_URL=postgresql://postgres:postgres@localhost:5432/postgres
./gradlew jvmTest koverHtmlReport        # unit and database tests, coverage report
./gradlew :image:installDist
server=./image/build/install/ostrom-server/bin/ostrom-server
OSTROM_DATABASE_MIGRATION_URL=postgresql://ostrom_owner:ostrom_owner@localhost:5432/ostrom $server migrate
OSTROM_DATABASE_URL=postgresql://ostrom_app:ostrom_app@localhost:5432/ostrom $server   # never as a superuser
curl localhost:8080/ready
```

Configuration keys and commands are listed in [`docs/MODULES.md`](docs/MODULES.md#configuration); how organizations
are kept apart in PostgreSQL is in [`docs/tenancy.md`](docs/tenancy.md).

## Repositories

| Repository | What it is | License |
| --- | --- | --- |
| [ostrom-core](https://github.com/ostrom-hq/ostrom-core) | Shared Kotlin Multiplatform library: API contract, workflow model, signed receipts | Apache-2.0 |
| [ostrom-cli](https://github.com/ostrom-hq/ostrom-cli) | The `ostrom` command line: runs coding agents under the team's rules | Apache-2.0 |
| **ostrom-server** (this one) | The server: gates, receipts, policies, the API | Apache-2.0 + ee |
| [ostrom-web](https://github.com/ostrom-hq/ostrom-web) | The web console | Apache-2.0 + ee |
| [ostrom-app](https://github.com/ostrom-hq/ostrom-app) | The iOS and Android app | Apache-2.0 + ee |
| [ostrom-e2e](https://github.com/ostrom-hq/ostrom-e2e) | Full-stack end-to-end and load tests | Apache-2.0 |
| [ostrom-site](https://github.com/ostrom-hq/ostrom-site) | ostrom.run: website, documentation, blog | Apache-2.0 |
| [ostrom-helm](https://github.com/ostrom-hq/ostrom-helm) | Helm chart and Docker Compose for self-hosting | Apache-2.0 |

## Contributing

Contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) (setup, tests, Conventional Commits, DCO), the
[Code of Conduct](CODE_OF_CONDUCT.md), and [SECURITY.md](SECURITY.md) to report a vulnerability privately. Questions
go to [Discussions](https://github.com/ostrom-hq/ostrom-server/discussions); help options are in [SUPPORT.md](SUPPORT.md).

## License

Apache License 2.0 ([LICENSE](LICENSE), [NOTICE](NOTICE)), except the `ee/` directory, under the Ostrom Enterprise License ([ee/LICENSE](ee/LICENSE)): you can read, modify and test that code, and running it in production needs an Ostrom Enterprise subscription. Details in [LICENSING.md](LICENSING.md).
