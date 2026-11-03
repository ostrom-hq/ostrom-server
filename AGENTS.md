# ostrom-server contributor instructions

This is the Ostrom server, Kotlin/Native (linuxX64, linuxArm64 in production, macosArm64 for development; + jvm for tests only), Ktor CIO, PostgreSQL (SQLite for tests and local) through sqlx4k. AGPL-3.0, `ee/` under the commercial license.

**The conventions are in [ostrom-core `docs/CONVENTIONS.md`](https://github.com/ostrom-hq/ostrom-core/blob/main/docs/CONVENTIONS.md) (local clone: `../ostrom-core/docs/CONVENTIONS.md`).** They follow Nathan Fallet's practices (teamcode, asonar,
pkg) and bind every contributor, human or agent. This file only summarizes the most important rules; when in
doubt, the conventions file wins, and it can be updated: re-read it before each pull request.

All repository content (code, comments, docs, commits, pull requests) is in English.

## Specific to this repository

Layers `domain`, `infrastructure`, `presentation`, `app`; the graph is checked by `scripts/check-conventions.py`. Every route declares who may call it and has its 401/404 tests (ENG-054). Every new request-path query comes with its EXPLAIN ANALYZE. Short k6 load test on every pull request, p95/p99 thresholds. Every customer table carries `org_id` under PostgreSQL Row-Level Security, with one end-to-end test per resource proving another organization cannot see or change it; console writes need a user session, agent tokens are scoped to their own session (CONVENTIONS.md section 16). Budgets tested in CI: start-up < 200 ms, API p95 < 50 ms under nominal load.

**End-to-end before the merge.** `ci-e2e` (`ci.yml`, through `e2e-journey.yml`) plays ostrom-e2e main against the
image the pull request builds: the isolation sweep, every scenario but the TeamCode import, and a short k6 of the gates.
`ci` needs it. So a pull request that changes an answer, adds or removes a route, or changes a limit brings its e2e
fix first: an ostrom-e2e pull request merged before it, as a contract change goes to ostrom-core first. A new route
needs its sweep case in ostrom-e2e (`PARAM_KINDS` in `scenarios/sweep.e2e.ts`, and the resource organization A makes),
or E2E-SWEEP-001 fails the server pull request.

## The 15 rules that matter most

1. Read CONVENTIONS.md before each pull request; it wins over habits from other projects.
2. Layers and graph: `api` ← `client` ← `cli`; `domain` ← `infrastructure`, `presentation` ← `app`. Domain and API depend on no other project module; the CLI never on server modules.
3. Interfaces `XxxRepository`, `XxxUseCase`, `XxxService`; implementations `XxxImpl` (never `IXxx`). One per file, grouped by feature.
4. Business rules live in use cases (`suspend operator fun invoke`), never in routes or CLI commands. Constructor injection; Koin wired only in the composition root, no `koin-ktor` plugin.
5. Ktor: typed `@Resource` classes from ostrom-core, one `Route.xxxRoutes(XxxRoutesDependencies)` per feature, mappers as extension functions.
6. Expected refusals are domain exceptions extending one `OstromException(kind)` base, routed by type in the single StatusPages `Throwable` handler (on Kotlin/Native StatusPages matches exact classes only) to 400/401/403/404/409/402 with a stable `ApiError` key; 500 only for the unforeseen, never echoing its message (ENG-007).
7. **Never swallow `CancellationException`**: rethrow it before any broad catch, no `runCatching` around suspend code, cleanup in `withContext(NonCancellable)`. `scripts/check-conventions.py` fails the build otherwise (INF-019).
8. Contract changes are additive: new fields optional, removal only after the optional phase; every sealed subclass has a stable `@SerialName` (ENG-009).
9. Never log credentials, tokens, prompts, response bodies or raw provider errors.
10. Configuration is read once from `OSTROM_*` variables into a typed object; no feature switch in an environment variable (ENG-050).
11. Tests: TDD (red first), `kotlin.test` + `runTest` + hand-written fakes in `commonTest` (no MockK), lowerCamelCase sentence names. 100 % lines and branches of commonMain via the test-only `jvm()` target (Kover), plus `linuxX64Test`; integration on real PostgreSQL, end-to-end on the real binary, load tests for the server.
12. Kotlin/Native only in production: no JVM-only library (Exposed, JDBC, Netty, Logback, MockK). Ktor CIO server, Ktor engine-defaults client, sqlx4k, cryptography-kotlin + OpenSSL prebuilt, Okio, Clikt.
13. Gradle: versions only in `gradle/libs.versions.toml` (same versions in every repository), convention plugin `ostrom.kmp` from `build-logic/`, module build files hold dependencies only. No heavy local build on the shared Mac: CI, or `~/git/ostrom-hq/.coord/build-lock.sh`.
14. CI: Linux on every pull request (detekt, check-conventions, jvmTest + koverVerify, linuxX64Test, PostgreSQL integration); macOS, Windows and arm64 runs on tags or `full_validation` only; actions pinned by SHA.
15. Git: one branch per topic in its own worktree, never push to `main`, Conventional Commits in English, squash-merge after green CI and a review with nothing blocking; evidence in the pull request, never in a shared status file.

## Coordination

Agents working at night read `~/git/ostrom-hq/.coord/BRIEF.md` first, log their work in `.coord/JOURNAL.md` and put
questions in `.coord/QUESTIONS.md` with their recommendation, then continue with it. Never modify another agent's
branch or worktree, never kill a process you did not start.
