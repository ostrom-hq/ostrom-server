# Adding a functional module

Every functional domain of the server (`identity`, `orgs`, `sessions`, `launches`, `gates`, `receipts`, `compiler`,
`workflow-runs`, `mcp-relay`, `secrets`, `usage`, `gateway`, `budgets`, `runners`, `automations`, `artifacts`,
`integrations-github`, `integrations-slack`, `sharing`, `governance`, `todos`) is a Gradle module under `modules/<name>/`.
Each one already exists as an empty skeleton, registered in the app: **the owner of a module only edits its own
directory**, plus its single line in `app/.../ServerModules.kt` if the order must change. `modules/instance` is the
complete reference example: copy its shape.

Read [`ostrom-core/docs/CONVENTIONS.md`](https://github.com/ostrom-hq/ostrom-core/blob/main/docs/CONVENTIONS.md)
first: it wins over this page wherever they differ.

## 1. Layout

```
modules/<name>/
  build.gradle.kts                      # plugins { id("ostrom.native-module") } (= ostrom.kmp) + project(":kernel")
  src/commonMain/kotlin/run/ostrom/server/<pkg>/
    <Name>Module.kt                     # object <Name>Module : ServerModule
    domain/                             # models, repository interfaces, use cases (XXX + XXXImpl)
    infrastructure/                     # repository implementations (sqlx4k), <Name>Migrations.kt
    presentation/                       # @Resource API classes, DTOs, routes, mappers
  src/commonTest/kotlin/run/ostrom/server/<pkg>/
    *Test.kt                            # unit tests with hand-written fakes, *PostgresTest.kt with serverTest
```

- **domain** depends on nothing but the kernel's `errors` and `signing` interfaces: no Ktor, no SQL.
- **infrastructure** implements the domain's interfaces; only it sees `io.github.smyrgeorge.sqlx4k`.
- **presentation** maps HTTP to use cases; routes receive a `<Name>RoutesDependencies` data class, in its own file
  (detekt `MatchingDeclarationName`: a file whose only class is not its name fails).
- A module never imports another module's `infrastructure` or `presentation`, never reads another module's tables.
  To use another domain, depend on its Gradle module and inject the **interface** it binds in Koin
  (`implementation(project(":modules:orgs"))`, then `get<GetOrgUseCase>()`); keep that graph acyclic.

## 2. The `ServerModule` object

```kotlin
object OrgsModule : ServerModule {
    override val name = "orgs"                       // [a-z][a-z0-9-]*, also the migration table: _migrations_orgs
    override val koinModule = module {
        single<OrgsRepository> { OrgsRepositoryImpl(get()) }          // get() = the sqlx4k Driver
        single<CreateOrgUseCase> { CreateOrgUseCaseImpl(get(), get()) } // get() = repository, kotlin.time.Clock
        single { OrgsRoutesDependencies(get()) }
    }
    override val migrations = OrgsMigrations
    override fun routes(route: Route, koin: Koin) = route.orgsRoutes(koin.get())
    // optional: override suspend fun initialize(koin: Koin) — after all migrations, before serving
    // optional: override fun start(scope: CoroutineScope, koin: Koin) — background work, cancelled on stop
}
```

The kernel binds, for every module: `Logger`, `Driver` (database), `InstanceSigner`, `kotlin.time.Clock`,
`TokenAuthenticator`, `PrincipalOrgResolver`, `MigrationRunner`, and the `HealthCheck`s. Use `Clock` from Koin, never `Clock.System`
directly, so tests can fix the time.

**Koin**: the app and the tests build Koin with `serverKoin(...)` (a local `koinApplication`, never `startKoin`,
never `install(Koin)`), **without overrides**: two modules binding the same type, or a module rebinding a kernel
type, fail the start. Two exceptions: the `TokenAuthenticator` and the `PrincipalOrgResolver`, bound by the
identity and orgs modules (`single<TokenAuthenticator> { … }`); until then `serverKoin` binds
`RejectingTokenAuthenticator` (every protected route answers 401) and `RejectingPrincipalOrgResolver` (no one is a
member of any organization: every org route answers 404). Only tests replace bindings, through
`serverTest(overrides = …)`.

## 3. Migrations

```kotlin
val OrgsMigrations = listOf(
    SchemaMigration(1, "create_orgs", """
        CREATE TABLE orgs_organizations (
            id UUID PRIMARY KEY,
            name TEXT NOT NULL,
            created_at TIMESTAMPTZ NOT NULL
        );
    """.trimIndent()),
)
```

- Each module has its **own** version sequence (1, 2, 3… without gaps) in its own table `_migrations_<name>`:
  two modules never compete for a number.
- **Every table starts with the module name** (`orgs_…`, `instance_…`).
- Migrations are applied by **`ostrom-server migrate`**, with the owner's credentials
  (`OSTROM_DATABASE_MIGRATION_URL`), under a PostgreSQL advisory lock: init containers or replicas that migrate at
  once wait for each other. The serving process never migrates: at start it checks that every migration is applied
  unchanged, and refuses to start otherwise (run `migrate` first).
- An applied migration is never edited (its checksum is verified by `migrate` and at each start); add a new version
  instead.
- **A table holding an organization's data** has `org_id`, `ENABLE` and `FORCE ROW LEVEL SECURITY` and its policy in
  the same migration (CONVENTIONS 16.1, [tenancy.md](tenancy.md)):
  `USING (org_id = CAST(nullif(current_setting('ostrom.org_id', true), '') AS uuid))` and the same `WITH CHECK`.
- PostgreSQL 15 or later: unique indexes use `NULLS NOT DISTINCT`. Some modules' migrations create the `ostrom_tenant`
  role their queries switch to (`SET LOCAL ROLE`): the owner that runs `migrate` needs `CREATEROLE`, and the serving
  role must be a member of `ostrom_tenant` (ci/database-roles.sql creates it beforehand and grants it to both, as a
  deployment must). Their `inTenant` converges on the kernel's `withOrganization`, which needs no role switch.
- Plain PostgreSQL. Statements are split on `;`. **No `::type` casts** (write `CAST(x AS type)`): the driver reads
  `:name` as a named parameter.
- Modules migrate in the order of `serverModules()`: a module whose tables reference another module's tables comes
  after it.

## 4. Repositories (sqlx4k)

```kotlin
driver.execute(
    Statement.create("INSERT INTO orgs_organizations (id, name, created_at) VALUES (:id, :name, :created_at)")
        .bind("id", org.id).bind("name", org.name).bind("created_at", org.createdAt),
).getOrThrow()

driver.fetchAll(Statement.create("SELECT id, name, created_at FROM orgs_organizations WHERE id = :id").bind("id", id))
    .getOrThrow().firstOrNull()?.let { row -> Org(row.get("id").asUuid(), row.get("name").asString(), row.get("created_at").asInstant()) }
```

- Always bind values (`:name`), never concatenate them into SQL.
- `Result` from sqlx4k is unwrapped with `getOrThrow()` in the repository; the domain never sees `Result`.
- Transactions: `driver.transaction { execute(…).getOrThrow(); … }`.
- **Tenant tables only through `driver.withOrganization(orgId) { tx -> … }`**: it opens the transaction and sets
  `ostrom.org_id` for it alone, so row-level security shows and accepts that organization's rows only. The repository
  of a tenant table receives the transaction, never the pool. Outside it, a tenant table shows no row.
- `Uuid` (kotlin.uuid), `Instant` (kotlin.time), `String`, numbers, `Boolean`, `ByteArray` bind directly.

## 5. Routes and errors

```kotlin
@Serializable @Resource("/api/v1/orgs") class OrgsApi { @Serializable @Resource("{id}") class Id(val parent: OrgsApi = OrgsApi(), val id: String) }

fun Route.orgsRoutes(dependencies: OrgsRoutesDependencies) = with(dependencies) {
    authenticated {
        get<OrgsApi.Id> { resource ->
            val member = call.requireOrgPrincipal(orgResolver, Uuid.parse(resource.id)) // 404 if not a member
            call.respond(getOrg(member).toOrgResponse())
        }
    }
}
```

- Every route is under `/api/v1/…` (the prefix of ostrom-core's `api/openapi.yaml`), except `/health`, which the
  kernel also serves as `/api/v1/health`.
- **Errors are thrown, never answered by hand**: a domain error subclasses `OstromException(ErrorKind.X, "message safe
  for the client")` (`class OrgNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Organization not found.")`).
  The kernel answers `{"error":{"type":"not_found","message":"…"}}` (CONVENTIONS section 3.3) with the kind's status;
  the request id is in the `X-Request-Id` header. A quota or plan limit is `ErrorKind.PAYMENT_REQUIRED` (402). Any
  other exception is a 500 whose message is logged, never sent. A unique-constraint violation is a 409.
- Protected routes go in `authenticated { }`; `call.requirePrincipal()` gives the `AuthenticatedPrincipal` (who the
  credential belongs to). Its `scopes` are explicit: an empty set grants no scope, never "everything".
- Org routes resolve the member the caller acts for with `call.requireOrgPrincipal(resolver, orgId)`: an
  `OrgPrincipal(orgId, memberId, role)`, `role` one of `OWNER`, `ADMIN`, `MEMBER`, `APPROVER`, `BILLING`; a caller who
  is not a member gets a 404, so the organization's existence does not leak. Every org query filters on `orgId`.
- A body that is not JSON is a 415, a malformed one a 400.
- DTOs and `@Resource` classes are `@Serializable`; they move to `ostrom-core` (the API contract) as soon as it is
  published, and the web/app/cli clients use them from there.

## 6. Logs

Inject `Logger` and log events with constant messages and named fields:
`logger.info("org created", mapOf("org_id" to org.id))`. Never log a token, a secret, a password or a request body.
Every request is already logged once by the kernel (method, route template — never the path, which may carry a ticket or a slug — status, duration, request id).

## 7. Tests

- Tests live in `commonTest` and run twice: on the **JVM** (`jvmTest`, a test-only target never shipped) where Kover
  requires **100 % of the lines and branches** of the module (`koverVerify`), and **natively** (`linuxX64Test`), which
  proves the real binary's behaviour. Native-only code (`nativeMain`) stays thin. Each module's own tests must cover
  it: another module's tests do not count. `@Serializable` classes are left out of the measure (a plugin-written
  branch in them cannot be reached): keep them free of logic, and test their JSON through the routes.
- A test that waits on real I/O with a timeout (`withTimeout`, the database health check) runs on
  `withContext(Dispatchers.Default)` inside `runTest`: `runTest`'s virtual clock would fire the timeout at once.
- **TDD**: the failing test first, for every behaviour ported from the prototype (`~/git/ostrom/mvp/server`, reuse its
  test cases).
- **Unit tests** (use cases): hand-written fakes (`InMemoryOrgsRepository`); MockK does not exist on Kotlin/Native.
  Coroutines with `runTest`; time from an injected `Clock`.
- **Database and route tests**: `serverTest(listOf(OrgsModule)) { server -> server.client.get("/api/v1/orgs") … }` starts
  a fresh PostgreSQL database, applies the migrations, runs `initialize`, serves the routes, and drops the database
  afterwards. Fakes replace real bindings through `overrides = module { single<TokenAuthenticator> { … } }`.
  Name these classes `*PostgresTest`.
- **Isolation tests** (one per tenant table, CONVENTIONS 16.1): `serverTest` and `TestDatabase` connect as the test
  database's owner, a superuser that bypasses row-level security. Prove the isolation as a serving role instead:
  `database.withRole { role, serving -> … }` creates a login role that owns nothing (grant it the module's tables),
  connects as it, and drops it afterwards; `kernel/…/TenancyPostgresTest.kt` is the model.
- Locally: `docker compose up -d postgres`,
  `export OSTROM_TEST_DATABASE_URL=postgresql://postgres:postgres@localhost:5432/postgres`, then
  `./gradlew :modules:orgs:jvmTest :modules:orgs:koverHtmlReport` (always through `.coord/build-lock.sh` on the shared
  Mac). Without the
  variable, database tests are skipped locally; CI sets `OSTROM_TEST_DATABASE_REQUIRED=true` so they can never be
  skipped there.

## 8. Before the PR

- CI job `ci` green: `scripts/check-conventions.py`, then
  `./gradlew detekt jvmTest koverVerify koverXmlReport linuxX64Test`, the binaries (`ci/build-linux.sh` leaves
  `dist/ostrom-server`) and the image smoke test.
- One PR per module step, small; the PR touches only `modules/<name>/` (and its line in `ServerModules.kt` if needed).
- `ee/` code: same shape, under `ee/modules/<name>/` (Gradle path `:ee:modules:<name>`, discovered like `modules/`),
  plus an `ee-module.properties` naming its `ServerModule` object; the app registers it after every AGPL module.
  AGPL code never depends on `ee/`, and CI compiles the app with `ee/` deleted (see `ee/README.md`).

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `OSTROM_CONFIG_FILE` | — | Optional `KEY=value` file; the environment wins over it |
| `OSTROM_HOST` | `0.0.0.0` | Listen address |
| `OSTROM_PORT` | `8080` | HTTP port |
| `OSTROM_DATABASE_URL` | required | `postgresql://[user[:password]@]host:5432/db`, no query string |
| `OSTROM_DATABASE_USER` | the URL's | Database user; wins over the URL's |
| `OSTROM_DATABASE_PASSWORD` | the URL's | Database password; wins over the URL's (required to use one with `%`, `@`, `:` or `/`) |
| `OSTROM_DATABASE_MAX_CONNECTIONS` | `10` | Pool size |
| `OSTROM_SIGNING_KEY_FILE` | `data/instance-key.pem` | Ed25519 key (PKCS#8 PEM), created with mode 0600 at first start; keep it on a persistent volume |
| `OSTROM_PREVIOUS_SIGNING_KEY_FILE` | — | For a key rotation only: the PEM file of the key the instance signed with before `OSTROM_SIGNING_KEY_FILE`, read (never created) at start to hand the new key over (`rotation` of `GET /receipts/keys`). A missing file stops the start. See [receipt-key-rotation.md](receipt-key-rotation.md) |
| `OSTROM_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |
| `OSTROM_TRUSTED_PROXIES` | none | Comma-separated networks (`10.0.0.0/8, fd00::/8`) of the reverse proxies whose `X-Forwarded-For` is believed: the client is the right-most hop that is not one of them. With none, the header is ignored and the client is the socket's peer |
| `OSTROM_CLIENT_ADDRESS_HEADER` | none | A header in which the trusted proxies give the client's address, read before `X-Forwarded-For` when the peer is one of them (`CF-Connecting-IP` behind Cloudflare). Absent, sent twice, not an IP address or a trusted proxy's: `X-Forwarded-For` decides. Without `OSTROM_TRUSTED_PROXIES`, or set to `X-Forwarded-For`, the server refuses to start. Set it only when clients cannot reach the server around that proxy (Cloudflare: Authenticated Origin Pulls on the ingress), or they could choose their address |
| `OSTROM_SHUTDOWN_DELAY_SECONDS` | `5` | 0 to 10: after SIGTERM, how long the server keeps serving with `/ready` at 503 before stopping the engine (then up to 15 s for requests in flight: at most 25 s, within the Helm grace period of 30 s) |
| `OSTROM_READS_PER_MINUTE` | `600` | 1 to 100 000: how many calls one subject (a person, a device, an agent, a service) makes per minute to the costly reads (sessions and their thread, activity, spending, receipts, the audit log, a gate and its wait, the approvals); past it, a 429 with `Retry-After`. Counted per replica |
| `OSTROM_READS_IN_FLIGHT` | `16` | 1 to 1 000: how many of those reads one subject holds at once; past it, a 429 with `Retry-After`. Counted per replica |
| `OSTROM_READ_WAITS_IN_FLIGHT` | `256` | 1 to 10 000: how many gate reads (`GET /gates/{id}`, whose `wait` holds the call up to 30 s) one subject holds at once, apart from the reads above; they count in `OSTROM_READS_PER_MINUTE` too. Past it, a 429 with `Retry-After`. Counted per replica |
| `OSTROM_MAX_CALLS_IN_PROGRESS` | from the memory limit | 1 to 100 000: how many calls this replica runs at once. Past it, a call waits in line for a place, before authentication and before its body is read, then gets a 503 `unavailable` with `Retry-After: 1`; at once when as many calls already wait as the ceiling. Unset, 150 per 512 MiB of container limit (at least 16; 256 without a limit), read again every 5 s (a failed read keeps the last). Held calls and `/health`, `/ready` are not counted |
| `OSTROM_MAX_HELD_CALLS` | from the memory limit | 1 to 100 000: the same, apart, half the ordinary ceiling when unset (75 per 512 MiB), for the calls that stay open: gate and confirmation waits, inference calls, MCP relay calls. A model call refused past it gets its 503 in the agent's API, as an overloaded provider answers (`overloaded_error` for Anthropic clients, `server_error` for OpenAI ones), with `Retry-After: 1` |
| `OSTROM_MAX_OPEN_CONNECTIONS` | from the memory limit | 1 to 100 000: the same, apart, for the connections open as long as their client keeps them: terminal WebSockets, the live usage stream. Unset, 100 per 512 MiB of container limit (at least 16; 170 without a limit): with 150, the three kinds full at once peaked at 495 MiB under 512 MiB (CHARGE 41668930337) |
| `OSTROM_CALL_QUEUE_SECONDS` | `5` | 0 to 60: how long a call past its ceiling waits in line for a place before its 503 |
| `KTOR_CURL_POLL_TIMEOUT` | `5` | Milliseconds the HTTP clients' engine (Ktor's Curl) waits in one poll while calls are open; set by the server at start unless already set. Ktor's own 100 ms made each outgoing exchange wait up to 100 ms while another exchange of the same client was open (an MCP tool call: 4 exchanges, 315 ms at p95 under load, 42 ms at 5) |
| `OSTROM_MCP_ALLOW_PRIVATE_UPSTREAMS` | `false` | `true` lets an organization's MCP server URL resolve to a loopback or private address (a self-hosted instance whose MCP servers live on its own network). Either way, every connection to such a URL is checked where it is made (SSRF protection): the host is resolved once, every answer is checked, and the connection dials an address that was checked, through a local `CONNECT` proxy of the process, so a name that resolves to a private address, or answers differently the second time (DNS rebinding), never reaches the server's network, IPv4 and IPv6 alike (mapped, 6to4, Teredo and NAT64 forms read as the IPv4 address they carry). By default only public addresses pass (not loopback, private, carrier-grade NAT, link-local, multicast, reserved or documentation ranges); with `true`, loopback and private ones pass, and link-local (cloud metadata) and unspecified ones never do. Redirects are not followed. Do not set `NO_PROXY` in the server's environment: libcurl would reach the hosts it names without the check. The chart's NetworkPolicy remains good practice, no longer the only barrier (docker compose has none) |
| `OSTROM_DATABASE_MIGRATION_URL` | required by `migrate` | The owner's connection, read by `ostrom-server migrate` only; never give it to the serving process |
| `OSTROM_DATABASE_MIGRATION_USER`, `OSTROM_DATABASE_MIGRATION_PASSWORD` | the URL's | The owner's credentials, as for `OSTROM_DATABASE_USER` |
| `OSTROM_SIGNUP` | `invite` | Who gets a new account: `invite`, the instance's setup link (`ostrom-server bootstrap`), an invitation or an organization's SSO only, and only owners and admins (or the setup link's person) create organizations; `open`, anyone who signs in, who may create organizations (ostrom.run). An access policy, not a feature switch. Serve logs a warning while an invite-only instance has no account |
| `OSTROM_ALLOW_TEST_ACCOUNTS` | — | `true` lets `ostrom-server seed-test-account` run; never set in production |
| `OSTROM_SECRETS_KEY` | — | The secrets key, base64 of 32 bytes (AES-256-GCM); wins over the file. Losing it loses every stored secret |
| `OSTROM_SECRETS_KEY_FILE` | `data/secrets.key` | File of the secrets key, created with mode 0600 at first start when `OSTROM_SECRETS_KEY` is unset; keep it on a persistent volume |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | — | OTLP/HTTP base URL (Grafana Cloud OTLP gateway, any collector); nothing is exported without it |
| `OTEL_EXPORTER_OTLP_HEADERS` | — | `Authorization=Basic%20…` for Grafana Cloud (keep it in a Secret) |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | `http/protobuf` | The only protocol written; any other value stops the start |
| `OTEL_SERVICE_NAME` | `ostrom-server` | `service.name` of traces, metrics and logs |
| `OTEL_RESOURCE_ATTRIBUTES` | — | `deployment.environment.name=production` |
| `OTEL_TRACES_SAMPLER_ARG` | `1` | Share of new traces recorded (0 to 1); a caller's sampled trace is always continued |
| `OTEL_SDK_DISABLED` | `false` | The standard OpenTelemetry variable, read as every SDK reads it (not an Ostrom feature switch): `true` exports nothing even with an endpoint |
| `OSTROM_LANGFUSE_HOST`, `_PUBLIC_KEY`, `_SECRET_KEY` | — | Model calls to Langfuse (the three together) |
| `OSTROM_POSTHOG_API_KEY`, `OSTROM_POSTHOG_HOST` | —, EU cloud | Product events to PostHog |
| `OSTROM_POD_NAME` | random | `service.instance.id` (`ServerConfig.instanceId`; the chart sets it from the pod name) |
| `OSTROM_USAGE_RETENTION_DAYS` | `400` | Days the usage ledger keeps its events (1 to 3650); older ones are purged every hour |
| `OSTROM_STRIPE_SECRET_KEY` (or `_FILE`) | — | Billing: Stripe secret key (`sk_test_…` or `sk_live_…`). Unset: self-hosted, limits come from the license key |
| `OSTROM_STRIPE_WEBHOOK_SECRET` (or `_FILE`) | required with a key | Billing: the endpoint's `whsec_…` signing secret; two comma-separated during a rotation |
| `OSTROM_STRIPE_API_BASE` | `https://api.stripe.com` | Billing: Stripe's API (stripe-mock in tests) |
| `OSTROM_CONSOLE_URL` | `https://app.ostrom.run` | Billing: the console, for Checkout and portal return pages and limit messages |
| `OSTROM_LICENSE_KEY` | — | Enterprise (`ee/`): the license key, `ostrom-license.v1.…`, verified offline at start; an invalid, expired (past 14 days of grace) or foreign key fails the start. Unset: no license |
| `OSTROM_LICENSE_KEY_FILE` | — | Enterprise (`ee/`): a file holding the license key, read when `OSTROM_LICENSE_KEY` is unset; missing or empty fails the start |

A module that needs its own keys reads them from the `ConfigSource` bound in Koin (`get<ConfigSource>()`: the
environment, then `OSTROM_CONFIG_FILE`), with the `OSTROM_<MODULE>_…` prefix, and adds them to this table. In a
`serverTest`, give it values with `overrides = module { single<ConfigSource> { MapConfigSource(mapOf(…)) } }`.

### Memory under the container limit

The server reads its container's memory limit (cgroup v2 `memory.max`, else v1) and holds itself under it, with no
key to set (the ceilings of calls can be set):

- Calls run on `Dispatchers.Default`, one thread per core. Ktor's CIO runs them on `Dispatchers.IO`, whose threads (up
  to 64) start on demand and never end, each with memory of its own: a burst of 100 unauthenticated requests took the
  server from 63 to 121 threads and OOMKilled it under 512 MiB (CHARGE 41637506577).
- The Kotlin/Native collector keeps its defaults, with no target or maximum set from the limit. A heap budget bounded
  under the limit grew Dispatchers.IO round after round and OOMKilled the server at the third round of a burst under 512
  MiB (CHARGE e2e 41693127618); without it, the same rounds stayed at 102 threads and peaked at 450 MiB (bisection,
  JOURNAL 03/11). The image's smoke test checks it.

- Calls at once are bounded (`OSTROM_MAX_CALLS_IN_PROGRESS`): each costs memory while it runs, about 1 MiB more past
  100 at once, so 500 approvals at once still OOMKilled the server under 512 MiB once the heap was bounded, where 200
  peaked at 377 MiB (CHARGE 41644506470). Past 150 calls per 512 MiB, a call waits in line up to 5 s
  (`OSTROM_CALL_QUEUE_SECONDS`), then is told 503 with `Retry-After: 1`; when as many calls already wait as the
  ceiling, at once. The limit is read again every 5 s, and a failed read keeps the last one.
- The calls that wait (gate and confirmation waits, inference calls, MCP relay calls, as slow as their upstream) count
  apart under their own ceiling (`OSTROM_MAX_HELD_CALLS`), half the ordinary one, so that both full stay near the 200
  calls measured at 377 MiB: a member who plugs in a slow MCP server fills only those places. The connections open as
  long as their client keeps them (terminal WebSockets, the live usage stream) count apart again
  (`OSTROM_MAX_OPEN_CONNECTIONS`, 100 per 512 MiB), so that permanent connections never take the waits' places. `/health` and `/ready` are never counted.

Without a limit (a plain process), the heap keeps the runtime's own tuning; the ordinary calls are bounded at 256,
the calls that wait at 128 and the open connections at 170.

### Module `gateway`

The inference gateway reads these keys at start (a malformed URL stops the server). An instance key serves every org
of the instance and comes before an org's own key (`/api/v1/inference/settings` names an org secret instead).

| Key | Default | Meaning |
|---|---|---|
| `OSTROM_GATEWAY_ANTHROPIC_API_KEY` | — | Anthropic API key for every org, when no shared Claude account serves |
| `OSTROM_GATEWAY_OPENAI_API_KEY` | — | OpenAI API key for every org (Responses, Chat Completions) |
| `OSTROM_GATEWAY_OPENROUTER_API_KEY` | — | OpenRouter key for every org (Chat Completions with a `vendor/model` name) |
| `OSTROM_GATEWAY_ANTHROPIC_URL` | `https://api.anthropic.com` | Anthropic base URL (`/v1/messages` is added); `http(s)` only |
| `OSTROM_GATEWAY_OPENAI_URL` | `https://api.openai.com` | OpenAI base URL (`/v1/responses`, `/v1/chat/completions`) |
| `OSTROM_GATEWAY_OPENROUTER_URL` | `https://openrouter.ai/api` | OpenRouter base URL (`/v1/chat/completions`) |
| `OSTROM_GATEWAY_CODEX_URL` | `https://chatgpt.com/backend-api/codex` | ChatGPT's Codex backend (`/responses`), for shared ChatGPT accounts |

A model call waits for its turn in the org's admission queue (TeamCode's admission), shared by every replica in
PostgreSQL (`gateway_admissions`, `gateway_admission_turns`, under an advisory lock of the org): while every shared
account it may use is busy, or while its member already runs as many calls as their limit (`memberConcurrency` in
the inference settings, 1 to 32). Interactive calls go first and a background one (`x-ostrom-workload: background`)
joins them after 30 s; then the member served longest ago, then arrival order. A call waits 90 s at most (503
`queue_timeout`); 128 calls wait per org and 8 per limited member (503 `queue_full`); a call whose replica stops
polling loses its turn within 5 s and its place within 15 s, and a running call's place lasts 15 minutes, renewed
every 5. Waiting holds no account, no budget and no database connection. The inference API's queue route counts the
waiting calls; every gateway refusal carries its code in `x-ostrom-error`.

### Module `governance`

A governance write (the rules, the gate policy, the model policy, a workflow's decision) made with a person's API
token, the CLI's (elevated by `ostrom login --admin` or not) or a personal one, is not applied: the route answers 202
with a confirmation that an owner or an admin answers on the console, at
`OSTROM_CONSOLE_URL/confirm/<id>` (audit CLI H1: an agent on the developer's machine can present the developer's
tokens). A confirmation shows what the write replaces and what it writes, lives 15 minutes, is answered once, and
each step (created, confirmed, refused, expired, failed) is an event of the organization's signed audit log. A token
never answers; a browser session and an `inference:admin` service key still write at once. A module that owns a
governance write binds a `GovernanceChange` named after itself (preview, apply) and calls `holdForConfirmation`
for a token; without this module loaded, a token's governance write is refused.

**Known limit: the confirmation is a fresh sign-in, not a passkey.** The server has no WebAuthn yet: confirming
needs a console sign-in less than 5 minutes old, the same step-up as a gate's yes (S2-54, `StepUp.WITHIN`), and
answers 403 `step_up_required` otherwise. An agent that can read the developer's mailbox could sign in again with an
e-mail link and confirm its own request; WebAuthn, planned after the go, closes that hole.

### Module `sharing`

The shared accounts' tokens are renewed at the providers' OAuth token endpoints. An operator can point them elsewhere
(a proxy, or the fake provider of the local demo, which renews any token). The refresh token is sent there: an endpoint
is `https`, or `http` to a local host only (`localhost`, `127.0.0.1`, `*.localhost`, `*.localtest.me`), judged on
the host the client parses; an endpoint with credentials (`@`), a fragment (`#`), a backslash, an IPv6 literal or a
port above 65535 is refused too. Anything else stops the server at start. A blank value counts as unset, so the
provider's own endpoint is used.

Each renewal also checks whose tokens they are. The provider names the account in its answer: `account.uuid` for
Claude, and for Codex the `chatgpt_account_id` of the `id_token`. When that account differs from the
`providerAccountId` the account was shared as (by the CLI or an import), the account becomes `wrong_account`: it
serves no call and the new pair is not stored. Pausing and resuming do not bring it back; it serves again once its
owner shares it again. An answer that names no account is accepted, because the field is optional at the providers.
A fake token endpoint must therefore name the account's own id, or none.

Renewals are coordinated in PostgreSQL, so any number of replicas share the accounts safely (TeamCode's
`refresh_pending`, docs/replicas.md there). Providers rotate refresh tokens: one sent twice disconnects the account.
Under the account row's lock (`SELECT … FOR UPDATE`), the replica that finds the tokens due (5 minutes before they
expire) commits a refresh intent (`refresh_pending`, its start by the database's clock) before it calls the provider.
Another replica that needs the account meanwhile looks again every 100 ms, up to 400 times, then leaves the account
for that call (the pool tries the next one); it never sends the token itself. The owner has 45 seconds, and a request
cancelled meanwhile does not cut the renewal short. It stores the new pair only while its intent is still the current
one (`credential_version`): a share made meanwhile wins. Any failure (refused, unreachable, an unreadable answer, the
45 seconds) leaves the provider's state unknown, so the account becomes `reconnect_required` and keeps its intent;
an intent older than 60 seconds is a dead replica's, and the next call that needs the account marks it
`reconnect_required` too. Only new tokens clear it: the owner sharing the account again, or an import. A share of an
account whose refresh is in flight (an intent younger than 60 seconds) answers 409 and changes nothing. Before every
replica runs this version, an older replica still renews with its in-memory lock and ignores the intent: finish the
rollout before relying on it.

The requests in flight on each account and the account each agent session used are in PostgreSQL too
(`sharing_slots`, `sharing_affinities`), so an account's `maxConcurrent` holds across every replica and a session keeps
its account (and the provider's prompt cache) whichever replica it reaches, as TeamCode's reservations and
`conversation_affinities`. A lease locks its candidates' rows, counts their unexpired slots and takes one in the same
transaction. A request still open renews its slot every 5 minutes, so a long stream keeps counting; a slot ends when
its request does, or within 15 minutes if its replica died. A slot the database fails to renew or take back is left to
that lease and fails no request. Once the provider has answered, writing what its headers say (the quota windows, a
cool-down after a 429) is bookkeeping too: the database failing there fails no request, and the answer reaches the
agent (TeamCode #165). A session sticks to its account while that account serves and has room, for an hour
after its last request; once moved, it stays on its new account. At most 1,000 sessions per member and 10,000 per
organization are remembered: past that, a new session is placed without being remembered, and no member evicts
another's. Only a SHA-256 digest of the session id is stored. `inFlight` in `GET /api/v1/accounts` and
`GET /api/v1/capacity` counts every replica.

Refresh ownership follows TeamCode's (`docs/capacity.md` « Refresh ownership » there, `sharing.handover.v1`): a share
from a sign-in is Ostrom's at once, since nothing else holds its refresh token. An account the TeamCode import creates
waits for a handover (`refreshOwnership` `pending_handover`, status `pending_handover`): TeamCode may still renew its
tokens, and two refreshers of one rotating refresh token disconnect the account, so it serves nothing and no replica
leases it. Each later import gives it TeamCode's current tokens. Once TeamCode no longer uses it, an admin hands it
over (the account's handover route, or the import's cutover run, which hands over every waiting account with
TeamCode's tokens), and Ostrom renews it from then on. The import's `accessOnly` trial (TeamCode's `--access-only`),
or the account's access-only route, serves an account with its access token and never renews it: once the token is within the 5-minute margin of its expiry, the account serves nothing until imported
again. Pausing and resuming never change the ownership; a member's own share of a waiting or access-only account is
refused (403 `account_in_use`), an admin's takes it over. A handover is refused (409 `account_refreshing`) while a
refresh intent is on the row, in flight or left by a failure: new tokens clear it first.

| Key | Default | Meaning |
|---|---|---|
| `OSTROM_SHARING_CLAUDE_TOKEN_URL` | `https://platform.claude.com/v1/oauth/token` | Token endpoint of shared Claude accounts |
| `OSTROM_SHARING_CODEX_TOKEN_URL` | `https://auth.openai.com/oauth/token` | Token endpoint of shared ChatGPT (Codex) accounts |

## Commands

| Command | What it does | Exit |
|---|---|---|
| `ostrom-server` (or `serve`) | Refuses a database role that is a superuser, has `BYPASSRLS`, or owns a table or is a member of its owner, checks the migrations, serves. On SIGTERM or SIGINT: `/ready` at 503 for `OSTROM_SHUTDOWN_DELAY_SECONDS`, then the engine stops and the pool closes | 0 after a graceful stop, 1 on a failure, 2 on an invalid configuration |
| `ostrom-server migrate` | Applies every module's migrations with `OSTROM_DATABASE_MIGRATION_URL`, under the advisory lock | 0, 1 on a failure, 2 without the URL |
| `ostrom-server bootstrap` | While the instance has no account, prints the first owner's setup link (`<OSTROM_CONSOLE_URL>/setup#token=osb_…`): it works once, for an hour, and a new one stops the earlier one. The person opens it and signs in with GitHub, Google or an e-mail link. The link is printed on the command's output only, never in the logs | 0, 2 when the instance already has an account or no module makes setup links |
| `ostrom-server seed-test-account <email>` | With `OSTROM_ALLOW_TEST_ACCOUNTS=true`, creates a test account through the module that binds `TestAccountSeeder` (identity) and prints `{"email","token","organizationId"}` on one line | 0, 2 when refused or when no module binds the seeder |

The probes: `GET /health` (liveness: 200 while the dependencies answer, also while draining) and `GET /ready`
(readiness: 200 `ready`, 503 `degraded` or `draining`), both unauthenticated.

`GET /api/v1/capabilities` (`Capabilities` and `ServerFeatures` in ostrom-core), served by the kernel, answers what
clients read after signing in: `apiVersion` (the contract version of the ostrom-core the server is built on,
`ServerFeatures.API_VERSION`), `serverVersion` (the build's `ostromVersion`, `0.0.0-dev` outside CI) and `features`, the
sorted union of every module's `ServerModule.features(koin)`. Any credential reads it, with no organization or scope; an
anonymous call gets 401 `unauthorized`. A module lists a key of ostrom-core's `ServerFeatures` only for what it serves
**and** what is configured on this instance, never for a planned operation:

| Module | Keys | Condition |
|---|---|---|
| `artifacts` | `artifacts.v1` | — |
| `automations` | `automations.v1`, `waits.v1` | — |
| `billing` | `billing.v1` | Stripe configured (`OSTROM_STRIPE_SECRET_KEY`) |
| `budgets` | `budgets.v1` | — |
| `compiler` | `packages.v1`, `workflows.v1` | — |
| `gates` | `gates.v1` | — |
| `gateway` | `inference.v1`, `inference.queue.v1` | — (an organization can set its own provider keys) |
| `governance` | `governance.v1` | — |
| `identity` | `audit.v1` | — |
| `import` | `import.teamcode.v1` | — |
| `integrations-github` | `github.v1`; `github.link.v1` | a GitHub App: the environment's (`OSTROM_GITHUB_APP_ID` or `_SLUG`) or the manifest flow's; the latter once that App's OAuth client (id and secret) is known |
| `mcp-relay` | `mcp.v1` | — |
| `notifications` | `notifications.v1`, `notifications.push.v1` | — (Expo's push service needs no key; `OSTROM_EXPO_ACCESS_TOKEN` only when the project requires one) |
| `receipts` | `receipts.v1` | — |
| `reports` | `reports.v1` | — |
| `rules` | `rules.v1`, `reports.v1` (the analysis) | — |
| `runners` | `runners.v1`; `runners.cloud.v1`; `runners.cloud.github.v1` | the second with a runtime driver (`OSTROM_RUNNERS_DRIVER` not `none`); the third also once members can link a GitHub account (`github.link.v1`) |
| `secrets` | `secrets.v1` | — |
| `sessions` | `sessions.v1` | — |
| `sharing` | `sharing.v1` | — |
| `todos` | `todos.v1` | — |
| `usage` | `usage.v1` | — |
| `workflow-runs` | `workflows.v1`, `notifications.v1` (the hooks' notifications) | — |

`slack.v1` and `license.v1` are not listed: the Slack module is an empty skeleton and the `ee/` license module serves
no route yet. A module that adds a feature, or a route behind a configuration, updates its `features` and this table.

The database roles of a deployment (the Helm chart creates them): an **owner**, which owns the schema and runs
`migrate`, and a **serving role** that owns nothing, `NOSUPERUSER NOBYPASSRLS`, granted `USAGE` on the schema and,
through `ALTER DEFAULT PRIVILEGES FOR ROLE <owner>`, `SELECT, INSERT, UPDATE, DELETE` on its tables and `USAGE, SELECT`
on its sequences. CI's smoke test sets them up the same way. The migrations' history tables (`_migrations_<name>`)
are the exception: after each pass, `migrate` revokes every privilege but `SELECT` on them from every role but the
owner, so the serving role reads the history (the start-up check) and never rewrites it.

## Known limits of the foundation

- **The in-memory limits are per replica**: the read limits (`OSTROM_READS_*`, `OSTROM_READ_WAITS_IN_FLIGHT`) count
  each pod apart, as a protection of its own CPU and memory. The sign-in rate limits are not among them: their windows
  (per client address, an IPv6 client by its /64, per e-mail recipient and per person) are rows of
  `identity_rate_limits`, by the hash of their key, counted in one statement under the key's row lock and timed by the
  database's clock, so every replica counts the same window; the windows over are purged every hour.
  Nor are the MCP relay's limits: the running operations and the operations of the minute, per member and
  per server, are rows of `mcp_relay_slots` and `mcp_relay_minutes`, counted under a lock on the server's row and by
  the database's clock, so every replica admits against the same count. A running operation renews its slot every
  5 minutes; a slot whose replica died frees itself within 15, and one the database fails to take back fails no call.
- **The live usage feed holds across replicas**: an event recorded on one replica reaches the live streams
  (`GET /api/v1/usage/live`) of every replica through PostgreSQL's `LISTEN`/`NOTIFY` (channel `ostrom_usage_feed`,
  kernel `DatabaseNotifications`, one listening connection per replica). A notification names events only
  (`<org>:<id>`, 64 at most), never what they hold: a replica with a subscriber of that org reads them from the
  ledger inside the org, one without reads nothing. Best effort like the feed itself: a notification sent while a
  replica's listener reconnects is lost to it; the ledger stays complete.
- **SQLite** (tests and local mode) is not wired yet: sqlx4k has a SQLite driver with the same `Driver` interface;
  repositories must stay portable SQL where they can.
- **The instance signer is provisional** (`kernel/signing`): it moves to `ostrom-core` once published; the
  `InstanceSigner` interface and the key file format (PKCS#8 PEM, `kid` = 16 hex of SHA-256 of the SPKI DER, as the
  prototype) stay.
- **API DTOs** live in each module until `ostrom-core` publishes the contract.

## Observability

The kernel traces and measures every request (`kernel/…/observability/Tracing.kt`): a SERVER span continuing the
caller's `traceparent`, named after the route template, returned in `traceresponse`; `/health` is measured, never
traced. A module adds its own work to the current trace and the server's metrics through Koin:

```kotlin
val telemetry = koin.get<Telemetry>()      // run.ostrom.core.telemetry
val metrics = koin.get<OstromMetrics>()    // run.ostrom.server.kernel.observability

telemetry.tracer.inSpan("gates.compile") { compile(request) }        // nested under the request's span
metrics.gateRequested(GateKind.MERGE.wire)
telemetry.capture(ProductEvents.gateRequested(userId, orgId, GateKind.MERGE, clock.now().toEpochMilliseconds()))
```

A model call (gateway, extraction) runs in
`telemetry.tracer.inSpan(ModelCall.SPAN_NAME, SpanKind.CLIENT, call.toAttributes(org.contentCapture))` and then
`metrics.modelCalled(call, duration)`: the span alone goes to Langfuse, prompts only when the org turned capture on.
The gateway's span (`SpanModelCallTracer`) covers every try of a call, from before planning to its record, under the
request's span; it carries the model, provider, tokens, cost, organization, session, outcome and status, and never the
prompt nor the answer (`ContentCapture.NONE`: the gateway does not read the org's capture choice yet).
Metric attributes are bounded values, never ids; a new metric is added to `OstromMetrics` and to
`ostrom-infra/observability/catalog.py` together. Event taxonomy and privacy rules: ostrom-core `docs/telemetry.md`.

Where the product metrics are emitted, each next to the code that decides, never inside it:

| Metric | Emitted by |
|---|---|
| `ostrom.gates.requested{kind}` | `gates` `MetricsGateChannel`, when an enforced gate opens (the notifier, after the commit) |
| `ostrom.gates.answered{answer,channel}`, `ostrom.gates.time_to_answer{answer}` | `gates` `MetricsGateChannel`, when a gate is decided |
| `ostrom.sessions.started{agent,where="local"}` | `sessions` `SessionSegments`, at a session's first segment |
| `ostrom.sessions.started{agent,where="cloud"}` | `runners` `MeteredCreateCloudSession`, once a cloud session is created |
| `ostrom.inference.cost_usd`, `.tokens`, `.duration` `{source,provider}` | `gateway` `MeteredModelCallObserver`, per relayed call that reached a provider |
| `ostrom.gates.pending` | `gates` `PendingGatesCount`, read every 30 s across organizations (`gates_pending_count()`) |
| `ostrom.sessions.active{where="local"}` | `sessions` `ActiveSessionsCount`, read every 30 s across organizations (`sessions_active_count`) |
