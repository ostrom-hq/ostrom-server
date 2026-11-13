# 0001. Run the server on the JVM, with TeamCode's server libraries

- Date: 2026-11-13
- Question: raised by Nathan Fallet: why is the server Kotlin/Native and not Ktor on the JVM? A long-running backend
  gains from the JVM's optimizations even if it uses more memory, and Native rules out libraries such as Exposed.
- Decision: made by Nathan, after the measurements below. The server moves to the JVM and aligns with TeamCode's
  server stack "to avoid reinventing the wheel". PostgreSQL stays.
- Evaluation, measurements and migration plan: Claude (Claude Code, the agent porting TeamCode's features to Ostrom).
  Its own recommendation was the JVM too, for the reasons below.
- Status: accepted. Replaces the "Kotlin/Native only" rule of AGENTS.md (rule 12) and ostrom-core's
  CONVENTIONS section 10 for the server. Each migration step updates those rules in the pull request that lands it.

## Context

Ostrom started on 2026-10-30 with one rule for every Kotlin repository: Kotlin/Native only, `jvm()` for tests and
coverage only (ostrom-core `docs/CONVENTIONS.md`). The reasons written down then were about the CLI: start-up time
(the hook has a time budget) and memory, from Nathan's own native-vs-JVM bench (6 to 15 times faster start, 2.5 to 13
times less memory, about 2 times slower on allocation-heavy loops), and the earlier TeamCode measures (JVM server
117 MB at rest and 470 to 590 MB under load, against 64 and 132 MB native). Nothing written weighed the server's own
case, a process that runs for weeks. The server took the rule as it was.

TeamCode, which Ostrom replaces, runs its server on the JVM: Ktor Netty, Exposed + JDBC + HikariCP, Flyway, MySQL,
koin-ktor, logback, a 1 GiB pod (`-XX:MaxRAMPercentage=70`).

### What Kotlin/Native cost the server (from its history)

- **No JVM library.** sqlx4k (Rust `sqlx` behind a Kotlin API, one maintainer) instead of JDBC/Exposed, with a
  home-made migration runner on top; a home-made OTLP exporter (about 2,500 lines in ostrom-core `telemetry`), since
  no OpenTelemetry SDK ships native artifacts; hand-written fakes, since MockK is JVM-only.
- **No TLS in Ktor's CIO client on Native.** Every outbound call goes through Ktor's Curl engine, with libcurl,
  OpenSSL and nghttp2 linked statically, plus a second static OpenSSL for `cryptography-kotlin`. Consequences:
  #89 (every https call failed while CIO was linked), the Curl poll fix (`HttpClients.kt`: each exchange cost up to
  100 ms while another one was open), and the SSRF guard of #373, which needed a local `CONNECT` proxy because the
  CIO client's `dnsResolver` hook, the one TeamCode uses, is unavailable. Security patches of OpenSSL and libcurl mean
  a new server build, not a new base image.
- **Memory management of the native runtime.** Ktor CIO's `Dispatchers.IO` threads never end on Native; each kept
  its memory: OOMKills under 512 MiB from 100 requests at once (#341), a heap budget that made it worse (CHARGE e2e
  41693127618), the paged allocator that never gave a burst back (252 to 269 MiB then OOMKilled, #360),
  `MALLOC_ARENA_MAX=2` in the image, calls moved to `Dispatchers.Default`, thread names through cinterop to tell the
  pools apart, and call ceilings sized from those measures (docs/MODULES.md, "Memory under the container limit").
- **Runtime differences found the hard way.** StatusPages matching only exact exception classes (CONVENTIONS 3),
  an uncaught coroutine exception ending the process, benches that stopped without a word
  (`IsolationMatrixPostgresTest`), no WebSockets in Ktor's native test client.
- **Build cost.** The native test job takes 17 min and the release link of the binary about 4 min on CI, against
  15 min for the whole JVM test job; the links needed two Gradle workers, stopped daemons and a swap file (#52, #53).

### What already works on the JVM

The whole server compiles and is tested on the JVM today (the `jvm()` target of every module, the Kover 100 % job,
all PostgreSQL suites). sqlx4k's JVM artifact uses `r2dbc-postgresql` and `r2dbc-pool`. Every `expect` of the server
already has a real JVM `actual` (environment, private files, process metrics, the runtime client). Running it as a
process only needed an entry point: about 100 lines (`JvmMain.kt` of the bench, ostrom-server#394).

## Measurements

### Same application, same load, same runner (ostrom-server#394, run 43444036220)

One `ubuntu-24.04` runner (4 vCPU, 16 GB), PostgreSQL 17 as a service. The server is pinned to CPUs 0-1 and k6 to
CPUs 2-3. The database is recreated and migrated before each variant. The load: 8 in 10 requests ask a gate, the
hook's hot path (a write plus the rules), as ostrom-e2e's `load/lib.js` does; the other two read the instance key and
list the person's teams. First 2 min of warm-up at 200 req/s, not counted. Then 12 min steady at 200 req/s (open loop:
latency). Then 3 min with 64 virtual users (closed loop: throughput). The native binary runs as the image runs it
(`MALLOC_ARENA_MAX=2`). The JVM is Temurin 21 with G1, first with `-Xmx256m` (a 384 MiB pod), then with default
ergonomics.

| | Native (release binary) | JVM, `-Xmx256m` | JVM, defaults |
| --- | --- | --- | --- |
| Start, exec to first `/ready` 200 (median of 5) | **84 ms** | 2,772 ms | 2,929 ms |
| RSS at ready / idle | **45 MiB** | 225 MiB | 249 MiB |
| Steady 200 req/s: p50 / p95 / p99 | 3.6 / 13.0 / 18.8 ms | **2.7 / 3.3 / 6.8 ms** | 3.0 / 3.9 / 7.5 ms |
| Gate ask p99, steady | 19.6 ms | **7.6 ms** | 8.4 ms |
| CPU per request, steady | 3.3 ms | **1.8 ms** | 2.0 ms |
| RSS peak during steady | **69 MiB** | 343 MiB | 351 MiB |
| Closed loop, 64 users: throughput | 478 req/s | **1,032 req/s** | 1,000 req/s |
| Closed loop: p50 / p99 | 142 / 207 ms | **67 / 101 ms** | 70 / 101 ms |
| Peak RSS overall / 30 s after the load | **82 / 81 MiB** | 343 / 337 MiB | 390 / 391 MiB |
| Errors | 0 of 254,046 | 0 of 353,704 | 0 of 347,899 |
| Threads after the load | 136 | 64 | 67 |

Once the JIT has warmed up, the JVM serves the same code at 2.2 times the throughput and with 45 % less CPU per
request. Its tail latency is a third of the native one: p99 6.8 ms against 18.8. In exchange it uses 4 to 5 times the
memory (about 340 MiB against 70 to 80 under load) and takes 2.8 s to start instead of 84 ms.

### The CI benches, in process (Build 43436008085, 43431727227, 43429247025)

The same benches run on both targets in every Build. A short bench runs on the JVM before the JIT has warmed up, so
native often wins there: a gate ask p95 of 4.8 to 5.4 ms against 16 to 20 ms. The one CPU-bound bench reverses that.
**Replaying 1,000 actions against 1,500 rules takes 12.9 s native against 1.4 to 2.0 s on the JVM**, 6 to 9 times
slower, in all three runs. That is regular expressions and allocations, the work the rules engine does all day.

### What the numbers mean for the cluster

- Production runs 1 replica with 128 Mi requested and 384 Mi limit. A JVM replica needs about 512 Mi requested and
  768 Mi to 1 Gi limit (TeamCode: 1 Gi). That is roughly 0.5 GiB more per replica: a few euros a month per replica on
  the shared cluster. Throughput per CPU doubles, so fewer replicas are needed for the same load.
- The start-up cost (3 s) matters for a CLI hook. It does not matter for a pod behind a readiness probe.
- Self-hosted single-machine installs pay the same memory. A 1 GiB VM still holds the server and PostgreSQL.

## Decision

1. **The server runs on the JVM** (Temurin 21 image), for production and for self-hosting. The CLI (ostrom-cli),
   the session runner (`runner/`, a per-session process with a PTY and no project dependency) and ostrom-core stay
   Kotlin Multiplatform with their native targets: start-up time is what they need.
2. **Align the server with TeamCode's libraries**, Nathan's choice: Ktor **Netty**, **Exposed** (DSL) with **JDBC**
   and **HikariCP**, **Flyway** migrations, **koin-ktor**, **logback**, and `ktor-server-routing-openapi` where it
   fits. Every other server-side library follows TeamCode's `gradle/libs.versions.toml` wherever it has an
   equivalent. ostrom-core's `api/openapi.yaml` stays the contract the clients use.
3. **PostgreSQL stays** (TeamCode is on MySQL). The owner/serving role split stays. Row-level security stays until
   Nathan decides on it (audit SD-08): the Exposed layer sets the organization on every transaction (`set_config`)
   whatever he decides.
4. TeamCode's database rules replace AGENTS.md rule 12, adapted to PostgreSQL, in the step that lands each part.
   Exposed DSL only, no SQL strings. Flyway migrations generated from the Exposed tables. `EXPLAIN (ANALYZE, BUFFERS)`
   on production-scale data for every new or changed query on a request path. No N+1. Indexes in filter order. Tests
   on real PostgreSQL only: SQLite is dropped.

## The options compared

| Option | For | Against |
| --- | --- | --- |
| **JVM with TeamCode's stack** (decided) | Measured: 2.2× the throughput, 45 % less CPU per request, a third of the p99, 6 to 9× faster on CPU-bound work. Mature GC instead of the allocator and thread workarounds. TLS, HTTP/2 and DNS hooks in the JDK (no Curl, no static OpenSSL; #373's proxy no longer needed). Security patches come with the base image. JFR, async-profiler, heap dumps, the OpenTelemetry agent. Exposed, JDBC, Flyway, MockK, Testcontainers. The same stack and rules as TeamCode, so its code and fixes port as they are. CI without native links. | 4 to 5 times the memory (about 340 MiB under this load), 2.8 s start-up, a bigger image. A migration across about 100,000 lines of server code: sqlx4k in about 150 files, 25 modules' migrations, the tenancy wrappers. One more runtime in the product (the CLI stays native). |
| JVM, keep sqlx4k and the current libraries | Smallest change: an entry point, the image and the chart. sqlx4k already runs on r2dbc-postgresql. | Keeps a single-maintainer driver, the home-made migration runner and raw SQL, unlike TeamCode, so its code cannot be ported as is. Nathan chose alignment. |
| Stay Kotlin/Native | 84 ms start-up, 45 to 80 MiB, a small static image. No migration. | Slower and more CPU per request once the JVM has warmed up (measured above). The OOM and allocator workarounds stay, so do the Curl/OpenSSL bundling and the libraries Native lacks (Exposed, JDBC drivers, the OpenTelemetry SDK, profilers). Each new need costs a workaround, as #373 did. |
| GraalVM native-image of the JVM server | JVM libraries with native start-up and memory. | Closed-world configuration for Exposed, Flyway and Ktor reflection; Flyway's classpath scan already failed under native-image in the TeamCode trial (`mvp/COORD/ARCHI.md`). It also gives up the JIT, the very gain measured here. |

## Migration plan (by Claude)

Each step keeps `main` green and is one or more reviewable pull requests. About 10 agents are editing `modules/*` at
the same time, so build and kernel changes land first and module conversions go one module per pull request.

1. **Kernel database layer, next to sqlx4k** (kernel `jvmMain`): HikariCP + PostgreSQL JDBC + Exposed + Flyway.
   One transaction entry point sets the organization (`set_config('ostrom.org_id', …, true)`) and the role, as
   `inOrg`/`withOrganization` do now. Pool and session timeouts as in TeamCode (connection 5 s, lock wait 5 s,
   statement timeout; audit SD-03). Flyway with the owner/serving role split and a check at start that every
   migration is applied. One Flyway baseline squashes the current schema (audit SD-06: no customer data yet).
   sqlx4k keeps working until the last module moves.
2. **The JVM becomes the production binary and image.** A JVM `main` in `app` (the native `Main.kt` ported, with a
   shutdown hook instead of the signal pipe). A Temurin 21 image (distroless Java or `eclipse-temurin:21-jre`, as
   TeamCode's Jib build), the image's smoke tests on it, and chart and production resources sized from the
   measurements (512 Mi requested, 1 Gi limit, `-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError`, audit
   SD-16). The server modules drop their native targets: their `nativeMain` dependency blocks go, and the few
   `nativeMain` files move to `jvmMain`. The runner keeps its native build. Graceful shutdown as in TeamCode (a
   660 s grace for streaming calls, SD-09). Liveness without the database and `/ready` with it (SD-04).
3. **Netty, koin-ktor and logback in `app` and `kernel`.** The memory workarounds made for Native come out with them:
   the call ceilings sized for 256 MiB, `MALLOC_ARENA_MAX`, thread names, the Curl settings.
4. **One reference module converted** to Exposed tables and repositories and Flyway migrations, with its tests on
   PostgreSQL. Then the "Server stack" section of `.parity/AGENT-BRIEF.md`, so that new code is written for the new
   stack.
5. **The other modules, one per pull request**, starting with the ones no open pull request touches.
6. **sqlx4k, SQLite and the native toolchain for the server leave the build.** Then the native CI job, the
   cinterop files and the Native-only rules of AGENTS.md and docs/MODULES.md go too. The outbound clients move to the
   JVM's engine, and the SSRF guard moves to the client's DNS hook as in TeamCode.

Risks: conflicts with the modules being edited (mitigated by the order above and one module per pull request). RLS
under Exposed: the decision on the SECURITY DEFINER functions is Nathan's (SD-08); until then they stay as Flyway SQL
and the kernel sets the organization on every transaction. The production database's migration history: squashed
into one baseline while there is no customer data (SD-06).

## Consequences

- The production image grows from about 30 MB to about 200 MB (a JRE). A replica's memory grows by about 0.5 GiB, and
  its throughput per CPU doubles.
- The JVM tests that already run become the tests of the shipped runtime. The native test job leaves the server's
  CI once step 6 is done.
- ostrom-core's CONVENTIONS section 10 keeps applying to ostrom-core and ostrom-cli. For ostrom-server it is replaced
  by this decision and by the rules each step writes into AGENTS.md.
