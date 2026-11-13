# ostrom-server contributor instructions

This is the Ostrom server, on the JVM in production (Java 21, a jib image for linux/amd64 and linux/arm64, [docs/decisions/0001](docs/decisions/0001-jvm-and-teamcode-stack.md)), Ktor CIO, PostgreSQL (SQLite for tests and local) through sqlx4k until the Exposed migration. The session runner (`runner/`) is the one Kotlin/Native program (`runner/gradle.properties`); the server modules have only the `jvm()` target. Apache-2.0, `ee/` under the commercial license.

**The conventions are in [ostrom-core `docs/CONVENTIONS.md`](https://github.com/ostrom-hq/ostrom-core/blob/main/docs/CONVENTIONS.md) (local clone: `../ostrom-core/docs/CONVENTIONS.md`).** They follow Nathan Fallet's practices (teamcode, asonar,
pkg) and bind every contributor, human or agent. This file only summarizes the most important rules; when in
doubt, the conventions file wins, and it can be updated: re-read it before each pull request.

All repository content (code, comments, docs, commits, pull requests) is in English.

## Specific to this repository

Layers `domain`, `infrastructure`, `presentation`, `app`; the graph is checked by `scripts/check-conventions.py`. Every route declares who may call it and has its 401/404 tests. Every new request-path query comes with its EXPLAIN ANALYZE. Short k6 load test on every pull request. Every customer table carries `org_id` under PostgreSQL Row-Level Security, with one end-to-end test per resource proving another organization cannot see or change it; console writes need a user session, agent tokens are scoped to their own session (CONVENTIONS.md section 16). Budgets: start-up < 200 ms, API p95 < 50 ms under nominal load.

**A Build never fails on a time in milliseconds** (orchestrator, JOURNAL 02:5x). A bench holds, in `ci.yml`, what no
runner's load changes: the queries one call sends (`maxQueries`, no N+1), the shape of its plans (no `Seq Scan` on a
filled table), the rows it reads, no deadlock; and a time only past ten times its budget, against a block. Every
time is measured through `checkTime` (testing module, `TimeBudget.kt`): shown in the job's summary, and held to its
budget by `perf.yml` (nightly and on dispatch), on the median of three runs. In ci-e2e, a k6 threshold on a time
(p95, p99) is reported, the others block (`scripts/k6-thresholds.py`); ostrom-e2e's nightly `chart-load.yml` holds the
time thresholds. A new bench follows the same split.

**End-to-end before the merge.** `ci-e2e` (`ci.yml`, through `e2e-journey.yml`) plays ostrom-e2e main against the
image the pull request builds: the isolation sweep, every scenario but the TeamCode import, and a short k6 of the gates.
`ci` needs it. So a pull request that changes an answer, adds or removes a route, or changes a limit brings its e2e
fix first: an ostrom-e2e pull request merged before it, as a contract change goes to ostrom-core first. A new route
needs its sweep case in ostrom-e2e (`PARAM_KINDS` in `scenarios/sweep.e2e.ts`, and the resource organization A makes),
or E2E-SWEEP-001 fails the server pull request. After the race, a burst guard (E2E-LOAD-012, `load/burst.js`) sends 100 requests
without a credential then 100 authenticated asks, and checks that the container neither stops nor is OOM-killed and
that its memory peak stays under the chart's limit (1Gi, the container runs with `--memory 1g`): a warning until #266 merges, blocking after.

## The 15 rules that matter most

1. Read CONVENTIONS.md before each pull request; it wins over habits from other projects.
2. Layers and graph: `api` ← `client` ← `cli`; `domain` ← `infrastructure`, `presentation` ← `app`. Domain and API depend on no other project module; the CLI never on server modules.
3. Interfaces `XxxRepository`, `XxxUseCase`, `XxxService`; implementations `XxxImpl` (never `IXxx`). One per file, grouped by feature.
4. Business rules live in use cases (`suspend operator fun invoke`), never in routes or CLI commands. Constructor injection; Koin wired only in the composition root, no `koin-ktor` plugin.
5. Ktor: typed `@Resource` classes from ostrom-core, one `Route.xxxRoutes(XxxRoutesDependencies)` per feature, mappers as extension functions.
6. Expected refusals are domain exceptions extending one `OstromException(kind)` base, routed by type in the single StatusPages `Throwable` handler to 400/401/403/404/409/402 with a stable `ApiError` key; 500 only for the unforeseen, never echoing its message.
7. **Never swallow `CancellationException`**: rethrow it before any broad catch, no `runCatching` around suspend code, cleanup in `withContext(NonCancellable)`. `scripts/check-conventions.py` fails the build otherwise.
8. Contract changes are additive: new fields optional, removal only after the optional phase; every sealed subclass has a stable `@SerialName`.
9. Never log credentials, tokens, prompts, response bodies or raw provider errors.
10. Only what the server needs to start is read from `OSTROM_*` variables, once, into a typed object; every other setting is an administrator's, in the console, stored in PostgreSQL with its secrets sealed (`SettingsSection`, docs/MODULES.md "Settings in the console"). No feature switch in an environment variable.
11. Tests: TDD (red first), `kotlin.test` + `runTest` + hand-written fakes in `commonTest` (no MockK), lowerCamelCase sentence names. 100 % lines and branches through the `jvm()` target (Kover), the server's runtime; the runner's `:runner:linuxX64Test` natively; integration on real PostgreSQL, end-to-end on the real binary, load tests for the server.
12. The JVM in production (docs/decisions/0001), on TeamCode's server libraries as each migration step lands them: today Ktor's CIO server and client, sqlx4k kept behind each module's repository interfaces; then Netty, Exposed + JDBC + HikariCP, Flyway, koin-ktor and logback. The image is TeamCode's: the Ktor Gradle plugin's `ktor { docker { } }` and jib (`image/`), `ostromhq/ostrom-server` on Docker Hub, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError`, 512Mi requested and 1Gi limit in the chart. Liveness (`/health`) checks only the process, readiness (`/ready`) the database; a stop drains the calls in flight up to `OSTROM_SHUTDOWN_GRACE_SECONDS` (5 s by default, 600 in the chart, whose grace period is 660 s).
13. Gradle: versions only in `gradle/libs.versions.toml` (same versions in every repository), convention plugin `ostrom.kmp` from `build-logic/`, module build files hold dependencies only. No heavy local build on the shared Mac: CI, or `~/git/ostrom-hq/.coord/build-lock.sh`.
14. CI: Linux on every pull request (detekt, check-conventions, jvmTest + koverVerify, the runner's linuxX64Test, PostgreSQL integration); macOS, Windows and arm64 runs on tags or `full_validation` only; actions pinned by SHA.
15. Git: one branch per topic in its own worktree, never push to `main`, Conventional Commits in English, squash-merge after green CI and a review with nothing blocking; evidence in the pull request, never in a shared status file.

## Coordination

Agents working at night read `~/git/ostrom-hq/.coord/BRIEF.md` first, log their work in `.coord/JOURNAL.md` and put
questions in `.coord/QUESTIONS.md` with their recommendation, then continue with it. Never modify another agent's
branch or worktree, never kill a process you did not start.
