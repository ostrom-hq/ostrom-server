# Adding a functional module

Every functional domain of the server (`identity`, `orgs`, `sessions`, `launches`, `gates`, `receipts`, `compiler`,
`workflow-runs`, `mcp-relay`, `secrets`, `usage`, `gateway`, `budgets`, `runners`, `automations`, `artifacts`,
`integrations-github`, `integrations-slack`, `sharing`, `governance`) is a Gradle module under `modules/<name>/`.
Each one already exists as an empty skeleton, registered in the app: **the owner of a module only edits its own
directory**, plus its single line in `app/.../ServerModules.kt` if the order must change. `modules/instance` is the
complete reference example: copy its shape.

Read [`ostrom-core/docs/CONVENTIONS.md`](https://github.com/ostrom-hq/ostrom-core/blob/main/docs/CONVENTIONS.md)
first: it wins over this page wherever they differ.

## 1. Layout

```
modules/<name>/
  build.gradle.kts                      # plugins { id("ostrom.native-module") } (= ostrom.kmp) + project(":kernel")
  src/commonMain/kotlin/dev/ostrom/server/<pkg>/
    <Name>Module.kt                     # object <Name>Module : ServerModule
    domain/                             # models, repository interfaces, use cases (XXX + XXXImpl)
    infrastructure/                     # repository implementations (sqlx4k), <Name>Migrations.kt
    presentation/                       # @Resource API classes, DTOs, routes, mappers
  src/commonTest/kotlin/dev/ostrom/server/<pkg>/
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
| `OSTROM_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |
| `OSTROM_TRUSTED_PROXIES` | none | Comma-separated networks (`10.0.0.0/8, fd00::/8`) of the reverse proxies whose `X-Forwarded-For` is believed: the client is the right-most hop that is not one of them. With none, the header is ignored and the client is the socket's peer |
| `OSTROM_CLIENT_ADDRESS_HEADER` | none | A header in which the trusted proxies give the client's address, read before `X-Forwarded-For` when the peer is one of them (`CF-Connecting-IP` behind Cloudflare). Absent, sent twice, not an IP address or a trusted proxy's: `X-Forwarded-For` decides. Without `OSTROM_TRUSTED_PROXIES`, or set to `X-Forwarded-For`, the server refuses to start. Set it only when clients cannot reach the server around that proxy (Cloudflare: Authenticated Origin Pulls on the ingress), or they could choose their address |
| `OSTROM_SHUTDOWN_DELAY_SECONDS` | `5` | 0 to 10: after SIGTERM, how long the server keeps serving with `/ready` at 503 before stopping the engine (then up to 15 s for requests in flight: at most 25 s, within the Helm grace period of 30 s) |
| `OSTROM_READS_PER_MINUTE` | `600` | 1 to 100 000: how many calls one subject (a person, a device, an agent, a service) makes per minute to the costly reads (sessions and their thread, activity, spending, receipts, the audit log, a gate and its wait, the approvals); past it, a 429 with `Retry-After`. Counted per replica |
| `OSTROM_READS_IN_FLIGHT` | `16` | 1 to 1 000: how many of those reads one subject holds at once; past it, a 429 with `Retry-After`. Counted per replica |
| `OSTROM_READ_WAITS_IN_FLIGHT` | `256` | 1 to 10 000: how many gate reads (`GET /gates/{id}`, whose `wait` holds the call up to 30 s) one subject holds at once, apart from the reads above; they count in `OSTROM_READS_PER_MINUTE` too. Past it, a 429 with `Retry-After`. Counted per replica |
| `OSTROM_MAX_CALLS_IN_PROGRESS` | from the memory limit | 1 to 100 000: how many calls this replica runs at once. Past it, a 503 `unavailable` with `Retry-After: 1`, at once, before authentication. Unset, 150 per 512 MiB of container limit (at least 16; 256 without a limit), read again at every call. Held calls and `/health`, `/ready` are not counted |
| `OSTROM_MAX_HELD_CALLS` | from the memory limit | 1 to 100 000: the same, apart, for the calls that stay open: gate and confirmation waits, terminal WebSockets, the live usage stream, inference calls |
| `OSTROM_DATABASE_MIGRATION_URL` | required by `migrate` | The owner's connection, read by `ostrom-server migrate` only; never give it to the serving process |
| `OSTROM_DATABASE_MIGRATION_USER`, `OSTROM_DATABASE_MIGRATION_PASSWORD` | the URL's | The owner's credentials, as for `OSTROM_DATABASE_USER` |
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
- The Kotlin heap is bounded: the collector starts at a quarter of the limit, its target grows to 45 % at most, and a
  thread that allocates past the target waits for the collection (`heap_max_mib` in the "server starting" log). A
  burst adds its memory in a second or two and the collector gives it back 10 to 15 s later (chart-load 41639797095,
  from `/proc/<pid>/smaps`).

- Calls at once are bounded (`OSTROM_MAX_CALLS_IN_PROGRESS`): each costs memory while it runs, about 1 MiB more past
  100 at once, so 500 approvals at once still OOMKilled the server under 512 MiB once the heap was bounded, where 200
  peaked at 377 MiB (CHARGE 41644506470). Past 150 calls per 512 MiB, a call is told 503 with `Retry-After: 1` before
  anything else runs. The calls that stay open (waits, WebSockets, streams) count apart under their own ceiling
  (`OSTROM_MAX_HELD_CALLS`), so that they neither take the other calls' places nor escape a ceiling; `/health` and
  `/ready` are never counted.

Without a limit (a plain process), the heap keeps the runtime's own tuning and both ceilings are 256.

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

| Key | Default | Meaning |
|---|---|---|
| `OSTROM_SHARING_CLAUDE_TOKEN_URL` | `https://platform.claude.com/v1/oauth/token` | Token endpoint of shared Claude accounts |
| `OSTROM_SHARING_CODEX_TOKEN_URL` | `https://auth.openai.com/oauth/token` | Token endpoint of shared ChatGPT (Codex) accounts |

## Commands

| Command | What it does | Exit |
|---|---|---|
| `ostrom-server` (or `serve`) | Refuses a database role that is a superuser, has `BYPASSRLS`, or owns a table or is a member of its owner, checks the migrations, serves. On SIGTERM or SIGINT: `/ready` at 503 for `OSTROM_SHUTDOWN_DELAY_SECONDS`, then the engine stops and the pool closes | 0 after a graceful stop, 1 on a failure, 2 on an invalid configuration |
| `ostrom-server migrate` | Applies every module's migrations with `OSTROM_DATABASE_MIGRATION_URL`, under the advisory lock | 0, 1 on a failure, 2 without the URL |
| `ostrom-server seed-test-account <email>` | With `OSTROM_ALLOW_TEST_ACCOUNTS=true`, creates a test account through the module that binds `TestAccountSeeder` (identity) and prints `{"email","token","organizationId"}` on one line | 0, 2 when refused or when no module binds the seeder |

The probes: `GET /health` (liveness: 200 while the dependencies answer, also while draining) and `GET /ready`
(readiness: 200 `ready`, 503 `degraded` or `draining`), both unauthenticated.

The database roles of a deployment (the Helm chart creates them): an **owner**, which owns the schema and runs
`migrate`, and a **serving role** that owns nothing, `NOSUPERUSER NOBYPASSRLS`, granted `USAGE` on the schema and,
through `ALTER DEFAULT PRIVILEGES FOR ROLE <owner>`, `SELECT, INSERT, UPDATE, DELETE` on its tables and `USAGE, SELECT`
on its sequences. CI's smoke test sets them up the same way. The migrations' history tables (`_migrations_<name>`)
are the exception: after each pass, `migrate` revokes every privilege but `SELECT` on them from every role but the
owner, so the serving role reads the history (the start-up check) and never rewrites it.

## Known limits of the foundation

- **The in-memory limits are per replica**: a rate limit keyed on the client address (now the real one behind
  `OSTROM_TRUSTED_PROXIES`) counts each pod apart, and so do the read limits (`OSTROM_READS_*`, `OSTROM_READ_WAITS_IN_FLIGHT`).
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
val telemetry = koin.get<Telemetry>()      // dev.ostrom.core.telemetry
val metrics = koin.get<OstromMetrics>()    // dev.ostrom.server.kernel.observability

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
