# Adding a functional module

Every functional domain of the server (`identity`, `orgs`, `sessions`, `launches`, `gates`, `receipts`, `compiler`,
`workflow-runs`, `mcp-relay`, `secrets`, `events`, `usage`, `gateway`, `budgets`, `runners`, `automations`,
`artifacts`, `integrations-github`, `integrations-slack`, `sharing`, `governance`, `todos`, `skills`, `memory`) is a
Gradle module under `modules/<name>/`. Each one already exists as an empty skeleton, registered in the app: **the owner
of a module only edits its own directory**, plus its single line in `app/.../ServerModules.kt` if the order must
change.
`modules/instance` is the complete reference example of a module's shape; `modules/todos` is the reference of its
database access on Exposed (section 4): copy both.

Read [`ostrom-core/docs/CONVENTIONS.md`](https://github.com/ostrom-hq/ostrom-core/blob/main/docs/CONVENTIONS.md)
first: it wins over this page wherever they differ.

## 1. Layout

```
modules/<name>/
  build.gradle.kts                      # plugins { id("ostrom.native-module") } (= ostrom.kmp) + project(":kernel")
  src/commonMain/kotlin/run/ostrom/server/<pkg>/
    <Name>Module.kt                     # object <Name>Module : ServerModule
    domain/                             # models, repository interfaces, use cases (XXX + XXXImpl)
    infrastructure/                     # repository implementations (Exposed), <Name>Tables.kt, <Name>Migrations.kt
    presentation/                       # @Resource API classes, DTOs, routes, mappers
  src/commonTest/kotlin/run/ostrom/server/<pkg>/
    *Test.kt                            # unit tests with hand-written fakes, *PostgresTest.kt with serverTest
```

- **domain** depends on nothing but the kernel's `errors` and `signing` interfaces: no Ktor, no SQL.
- **infrastructure** implements the domain's interfaces; only it sees `org.jetbrains.exposed` (and, in a module not
  yet moved, `io.github.smyrgeorge.sqlx4k`).
- **presentation** maps HTTP to use cases; routes receive a `<Name>RoutesDependencies` data class, in its own file
  (detekt `MatchingDeclarationName`: a file whose only class is not its name fails).
- A module never imports another module's `infrastructure` or `presentation`, never reads another module's tables.
  To use another domain, depend on its Gradle module and inject the **interface** it binds in Koin
  (`implementation(project(":modules:orgs"))`, then `get<GetOrgUseCase>()`); keep that graph acyclic.

## 2. The `ServerModule` object

```kotlin
object OrgsModule : ServerModule {
    override val name = "orgs"                       // [a-z][a-z0-9-]*, also the history table: flyway_orgs
    override val koinModule = module {
        single<OrgsRepository> { OrgsRepositoryImpl(get(), get()) }    // get() = the JdbcDatabase, kotlin.time.Clock
        single<CreateOrgUseCase> { CreateOrgUseCaseImpl(get(), get()) } // get() = repository, kotlin.time.Clock
        single { OrgsRoutesDependencies(get()) }
    }
    override val migrations = OrgsMigrations
    override fun routes(route: Route, koin: Koin) = route.orgsRoutes(koin.get())
    // optional: override suspend fun initialize(koin: Koin) — after all migrations, before serving
    // optional: override fun start(scope: CoroutineScope, koin: Koin) — background work, cancelled on stop
}
```

**Periodic work that one replica runs at a time** goes through the kernel's `ScheduledWork` (bound by the instance
module on its `instance_scheduled_work` table, TeamCode's `SqlScheduledWork`): the module's `start` loop calls
`work.runDue("module/job", interval, timeout) { … }` (TeamCode used 60 s and 45 s by default) as often as it likes, and the job runs once per interval across
every replica, the lease claimed and released on the database's clock. A module that needs it lists the instance
module before it (it always does: the instance module comes first).
`koin.scheduledWork()` gives that lease, or, in a test that loads a module without the instance module, one that runs
every call; `work.repeatDue(ScheduledJob(name, interval, timeout), logger, failure) { … }` is the loop: it runs the job when it is
due, waits its interval after a run and asks again every minute at most while another replica holds it, and logs a
failed or overrunning run by its type only. The jobs on it: `mcp/purge` (hourly) and `mcp/retain` (each minute),
`identity/purge`, `launches/purge` and `usage/purge` (hourly), `events/purge` (each minute) and `sessions/sweep`
(every 30 s).

The kernel binds, for every module: `Logger`, `JdbcDatabase` (Exposed over the HikariCP pool), `Driver` (sqlx4k, for
the modules not yet on Exposed), `InstanceSigner`, `kotlin.time.Clock`,
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

- Each module has its **own** version sequence (1, 2, 3… without gaps) in its own Flyway history table
  `flyway_<name>`: two modules never compete for a number, and a version used twice is refused. A database migrated
  before by sqlx4k (`_migrations_<name>`) is adopted: Flyway's history starts with a baseline at its last version,
  once every version it applied is checked against the code.
- **Every table starts with the module name** (`orgs_…`, `instance_…`).
- Migrations are applied by **`ostrom-server migrate`**, with the owner's credentials
  (`OSTROM_DATABASE_MIGRATION_URL`), under a PostgreSQL advisory lock: init containers or replicas that migrate at
  once wait for each other. A migration waits at most 3 s for a table lock (`lock_timeout`), and the pass runs again
  after a pause when it gave up on one (SQLSTATE 55P03), up to 5 times. The serving process never migrates: at start it checks that every migration is applied
  unchanged, and refuses to start otherwise (run `migrate` first).
- An applied migration is never edited (its checksum is verified by `migrate` and at each start); add a new version
  instead.
- **A table holding an organization's data** has `org_id`, `ENABLE` and `FORCE ROW LEVEL SECURITY` and its policy in
  the same migration (CONVENTIONS 16.1, [tenancy.md](tenancy.md)):
  `USING (org_id = CAST(nullif(current_setting('ostrom.org_id', true), '') AS uuid))` and the same `WITH CHECK`.
- PostgreSQL 15 or later: unique indexes use `NULLS NOT DISTINCT`. Some modules' migrations create the `ostrom_tenant`
  role their queries switch to (`SET LOCAL ROLE`): the owner that runs `migrate` needs `CREATEROLE`, and the serving
  role must be a member of `ostrom_tenant` (ci/database-roles.sql creates it beforehand and grants it to both, as a
  deployment must). On Exposed, the kernel's `JdbcDatabase.inOrg` does that switch (section 4).
- Plain PostgreSQL, run by Flyway over JDBC as written (Java migrations: no placeholder replacement, no splitting).
  Write `CAST(x AS type)` rather than `::type`: style only now, kept so that every migration reads alike.
- Modules migrate in the order of `serverModules()`: a module whose tables reference another module's tables comes
  after it.

## 4. Repositories (Exposed)

New code and every module moved off sqlx4k use **Exposed's typed DSL over the kernel's `JdbcDatabase`** (TeamCode's
stack, [docs/decisions/0001](decisions/0001-jvm-and-teamcode-stack.md)). `modules/todos` is the reference: its
`infrastructure/TodosTables.kt` and `TodosRepositoryImpl.kt` show every pattern below.

**Tables** (`infrastructure/<Name>Tables.kt`): one `internal object` per table, its columns named as the migration
creates them. The migrations stay the schema's only source: never `SchemaUtils.create`, and a column added to a table
object is added by a new migration first.

```kotlin
internal object OrgsOrganizations : Table("orgs_organizations") {
    val id = uuid("id")                    // kotlin.uuid.Uuid
    val name = text("name")
    val note = text("note").nullable()
    val createdAt = instant("created_at")  // the kernel's TIMESTAMPTZ column of kotlin.time.Instant
    override val primaryKey = PrimaryKey(id)
}
```

**Queries** (`infrastructure/<Name>RepositoryImpl.kt`), the repository constructed with the `JdbcDatabase`:

```kotlin
override suspend fun find(orgId: Uuid, id: Uuid): Org? = database.inOrg(orgId) {
    OrgsOrganizations.select(OrgsOrganizations.id, OrgsOrganizations.name, OrgsOrganizations.createdAt)
        .where { (OrgsOrganizations.orgId eq orgId) and (OrgsOrganizations.id eq id) }
        .singleOrNull()
        ?.let { Org(it[OrgsOrganizations.id], it[OrgsOrganizations.name], it[OrgsOrganizations.createdAt]) }
}

override suspend fun rename(orgId: Uuid, id: Uuid, name: String): Boolean = database.inOrg(orgId) {
    OrgsOrganizations.update({ (OrgsOrganizations.orgId eq orgId) and (OrgsOrganizations.id eq id) }) {
        it[OrgsOrganizations.name] = name
    } > 0
}
```

- **One transaction per repository call**: `database.query { }`, or `database.inOrg(orgId) { }` for a tenant table.
  The block is not `suspend` and runs on `Dispatchers.IO`: no network call, no other repository, no `delay` inside a
  transaction (CRL-03); read what the use case needs, decide in the domain, write in a second call or pass the rule in
  as a plain function (`mutate(orgId, id) { current -> next }`, as `TodosRepository.mutate`).
- **No hand-written SQL**: `select`, `insert`, `insertIgnore` (`ON CONFLICT DO NOTHING`), `upsert`
  (`ON CONFLICT … DO UPDATE`), `update`, `deleteWhere`, `forUpdate()`, `orderBy`, `limit`, joins. Values are always
  bound by Exposed. `exec("…")` is for the kernel only (role switches, advisory locks).
- **Every read lists its columns** (`select(T.a, T.b)` or `select(T.columns)`), and a credential table's read names only
  the columns it needs, never `selectAll()` (TeamCode fix 1322acbc). Every list read has a `limit`.
- **Tenant tables, two walls**: every query filters on `T.orgId eq orgId` itself (the typed org filter, SD-08), and
  runs in `database.inOrg(orgId) { }`, which switches the transaction to `ostrom_tenant` and sets `ostrom.org_id`, so
  the table's row-level security policy still refuses another organization's rows. Keep both until the step that
  drops row-level security; outside `inOrg` a tenant table shows no row and accepts none.
- A change that reads then writes locks what it read: `select(…).where { … }.forUpdate()` in the same transaction.
- Exposed never retries a transaction (`defaultMaxAttempts = 1`). A refusal the domain expects (a duplicate) is an
  `insertIgnore`/`upsert` or a check in the same transaction, not a caught exception.
- JSON columns are `text` written and read by the module's own serializer (`TodoJson`); Exposed's `json` columns are
  not used yet.
- The domain never sees Exposed types: rows become domain models in the repository (`ResultRow.toX()`).

A module not yet moved keeps its sqlx4k repository (`driver.fetchAll(Statement.create(…).bind(…))`,
`driver.withOrganization(orgId) { tx -> … }`, `Result.getOrThrow()`) until its own step: move a module whole, every
repository of it in one PR, its tests on Flyway (section 7).

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

- Tests are written `fun x() { runTest { … } }`, never `fun x() = runTest { … }`: JUnit skips a test function that
  returns a value (TeamCode fix 6112985f). `fun x() = serverTest(…) { … }` is fine: it returns `Unit`.
- Tests live in `commonTest` (and `jvmTest` for JVM-only code) and run on the **JVM** (`jvmTest`), the server's runtime
  ([docs/decisions/0001](decisions/0001-jvm-and-teamcode-stack.md)), where Kover requires **100 % of the lines and
  branches** of the module (`koverVerify`). The server modules have no native target any more. Each module's own
  tests must cover it: another module's tests do not count. `@Serializable` classes are left out of the measure (a plugin-written
  branch in them cannot be reached): keep them free of logic, and test their JSON through the routes.
- A test that waits on real I/O with a timeout (`withTimeout`, the database health check) runs on
  `withContext(Dispatchers.Default)` inside `runTest`: `runTest`'s virtual clock would fire the timeout at once.
- **TDD**: the failing test first, for every behaviour ported from the prototype (`~/git/ostrom/mvp/server`, reuse its
  test cases).
- **Unit tests** (use cases): hand-written fakes (`InMemoryOrgsRepository`), no MockK (CONVENTIONS).
  Coroutines with `runTest`; time from an injected `Clock`.
- **Database and route tests**: `serverTest(listOf(OrgsModule)) { server -> server.client.get("/api/v1/orgs") … }`
  starts a fresh PostgreSQL database, applies the migrations with Flyway as `ostrom-server migrate` does, starts the
  kernel with its `JdbcDatabase` as the serving role (which checks Flyway's histories), runs `initialize`, serves the
  routes, and drops the database afterwards. A test that applies migrations itself (a part of them first, to test a
  data migration) uses `database.migrationRunner(logger)`, Flyway on the owner's connections. Fakes replace real
  bindings through `overrides = module { single<TokenAuthenticator> { … } }`. Name these classes `*PostgresTest`.
- **Isolation tests** (one per tenant table, CONVENTIONS 16.1): `serverTest`'s repositories and routes run as the
  test database's serving role, which bypasses nothing, as a deployment's. To prove a policy on its own, as another
  role: `database.withRole { role, serving -> … }` creates a login role that owns nothing (grant it the module's tables),
  connects as it, and drops it afterwards; `kernel/…/TenancyPostgresTest.kt` is the model, and
  `kernel/…/database/JdbcTenancyPostgresTest.kt` on Exposed. A module's own isolation test reads and writes one
  organization's rows through its repository and checks another organization sees none (`TodosPostgresTest`,
  `anotherOrganizationNeitherReadsNorChangesAList`).
- Locally: `docker compose up -d postgres`,
  `export OSTROM_TEST_DATABASE_URL=postgresql://postgres:postgres@localhost:5432/postgres`, then
  `./gradlew :modules:orgs:jvmTest :modules:orgs:koverHtmlReport` (always through `.coord/build-lock.sh` on the shared
  Mac). Without the
  variable, database tests are skipped locally; CI sets `OSTROM_TEST_DATABASE_REQUIRED=true` so they can never be
  skipped there.

## 8. Before the PR

- CI job `ci` green: `scripts/check-conventions.py`, then
  `./gradlew detekt jvmTest koverVerify koverXmlReport`, the runner's and the push relay's `:runner:linuxX64Test :push:linuxX64Test`, the image (`./gradlew
  :image:publishImageToLocalRegistry`, jib) and its smoke test.
- One PR per module step, small; the PR touches only `modules/<name>/` (and its line in `ServerModules.kt` if needed).
- `ee/` code: same shape, under `ee/modules/<name>/` (Gradle path `:ee:modules:<name>`, discovered like `modules/`),
  plus an `ee-module.properties` naming its `ServerModule` object; the app registers it after every Apache-2.0 module.
  Apache-2.0 code never depends on `ee/`, and CI compiles the app with `ee/` deleted (see `ee/README.md`).

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `OSTROM_CONFIG_FILE` | — | Optional `KEY=value` file; the environment wins over it |
| `OSTROM_HOST` | `0.0.0.0` | Listen address |
| `OSTROM_PORT` | `8080` | HTTP port |
| `OSTROM_DATABASE_URL` | required | `postgresql://[user[:password]@]host:5432/db`, no query string |
| `OSTROM_DATABASE_USER` | the URL's | Database user; wins over the URL's |
| `OSTROM_DATABASE_PASSWORD` | the URL's | Database password; wins over the URL's (required to use one with `%`, `@`, `:` or `/`) |
| `OSTROM_DATABASE_MAX_CONNECTIONS` | `10` | 4 to 64: the database connections one replica opens at most, its pools together (see "Database connections" below). Read at start: the pool opens before any setting can be read from the database |
| `OSTROM_SIGNING_KEY_FILE` | `data/instance-key.pem` | Ed25519 key (PKCS#8 PEM), created with mode 0600 at first start; keep it on a persistent volume |
| `OSTROM_PREVIOUS_SIGNING_KEY_FILE` | — | For a key rotation only: the PEM file of the key the instance signed with before `OSTROM_SIGNING_KEY_FILE`, read (never created) at start to hand the new key over (`rotation` of `GET /receipts/keys`). A missing file stops the start. See [receipt-key-rotation.md](receipt-key-rotation.md) |
| `OSTROM_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |
| `OSTROM_TRUSTED_PROXIES` | none | Comma-separated networks (`10.0.0.0/8, fd00::/8`) of the reverse proxies whose `X-Forwarded-For` is believed: the client is the right-most hop that is not one of them. With none, the header is ignored and the client is the socket's peer |
| `OSTROM_CLIENT_ADDRESS_HEADER` | none | A header in which the trusted proxies give the client's address, read before `X-Forwarded-For` when the peer is one of them (`CF-Connecting-IP` behind Cloudflare). Absent, sent twice, not an IP address or a trusted proxy's: `X-Forwarded-For` decides. Without `OSTROM_TRUSTED_PROXIES`, or set to `X-Forwarded-For`, the server refuses to start. Set it only when clients cannot reach the server around that proxy (Cloudflare: Authenticated Origin Pulls on the ingress), or they could choose their address |
| `OSTROM_SHUTDOWN_DELAY_SECONDS` | `5` | 0 to 10: after SIGTERM, how long the server keeps serving with `/ready` at 503 before it waits for the calls in flight |
| `OSTROM_SHUTDOWN_GRACE_SECONDS` | `5` | 0 to 600: then, how long the server waits at most for the ordinary and held calls in flight (a model's stream, a gate's wait) to end before stopping the engine, which cuts the open connections (terminal, live usage stream) their clients reopen elsewhere. The Helm chart sets 600 and a grace period of 660 s (TeamCode's, audit SD-09); the short default fits `docker stop` |
| `OSTROM_READS_PER_MINUTE` | `600` | 1 to 100 000: how many calls one subject (a person, a device, an agent, a service) makes per minute to the costly reads (sessions and their thread, activity, spending, receipts, the audit log, a gate and its wait, the approvals); past it, a 429 with `Retry-After`. Counted per replica |
| `OSTROM_READS_IN_FLIGHT` | `16` | 1 to 1 000: how many of those reads one subject holds at once; past it, a 429 with `Retry-After`. Counted per replica |
| `OSTROM_READ_WAITS_IN_FLIGHT` | `256` | 1 to 10 000: how many gate reads (`GET /gates/{id}`, whose `wait` holds the call up to 30 s) one subject holds at once, apart from the reads above; they count in `OSTROM_READS_PER_MINUTE` too. Past it, a 429 with `Retry-After`. Counted per replica |
| `OSTROM_MAX_CALLS_IN_PROGRESS` | from the memory limit | 1 to 100 000: how many calls this replica runs at once. Past it, a call waits in line for a place, before authentication and before its body is read, then gets a 503 `unavailable` with `Retry-After: 1`; at once when as many calls already wait as the ceiling. Unset, 150 per 512 MiB of container limit (at least 16; 256 without a limit), read again every 5 s (a failed read keeps the last). Held calls and `/health`, `/ready` are not counted |
| `OSTROM_MAX_HELD_CALLS` | from the memory limit | 1 to 100 000: the same, apart, half the ordinary ceiling when unset (75 per 512 MiB), for the calls that stay open: gate and confirmation waits, inference calls, MCP relay calls. A model call refused past it gets its 503 in the agent's API, as an overloaded provider answers (`overloaded_error` for Anthropic clients, `server_error` for OpenAI ones), with `Retry-After: 1` |
| `OSTROM_MAX_OPEN_CONNECTIONS` | from the memory limit | 1 to 100 000: the same, apart, for the connections open as long as their client keeps them: terminal WebSockets, the live usage stream. Unset, 100 per 512 MiB of container limit (at least 16; 170 without a limit): with 150, the three kinds full at once peaked at 495 MiB under 512 MiB (CHARGE 41668930337) |
| `OSTROM_CALL_QUEUE_SECONDS` | `5` | 0 to 60: how long a call past its ceiling waits in line for a place before its 503 |
| `OSTROM_MCP_ALLOW_PRIVATE_UPSTREAMS` | `false` | `true` lets an organization's MCP server URL resolve to a loopback or private address (a self-hosted instance whose MCP servers live on its own network). Either way, every connection to such a URL is checked where it is made (SSRF protection): the host is resolved once, every answer is checked, and the connection dials an address that was checked, through a local `CONNECT` proxy of the process, so a name that resolves to a private address, or answers differently the second time (DNS rebinding), never reaches the server's network, IPv4 and IPv6 alike (mapped, 6to4, Teredo and NAT64 forms read as the IPv4 address they carry). By default only public addresses pass (not loopback, private, carrier-grade NAT, link-local, multicast, reserved or documentation ranges); with `true`, loopback and private ones pass, and link-local (cloud metadata) and unspecified ones never do. Redirects are not followed. Do not set `NO_PROXY` in the server's environment: libcurl would reach the hosts it names without the check. The chart's NetworkPolicy remains good practice, no longer the only barrier (docker compose has none) |
| `OSTROM_DATABASE_MIGRATION_URL` | required by `migrate` | The owner's connection, read by `ostrom-server migrate` only; never give it to the serving process |
| `OSTROM_DATABASE_MIGRATION_USER`, `OSTROM_DATABASE_MIGRATION_PASSWORD` | the URL's | The owner's credentials, as for `OSTROM_DATABASE_USER` |
| `OSTROM_MAIL_DIR` | — | A developer's machine and end-to-end tests: e-mails are written as files in this directory instead of sent; ignored once a mail provider is set in the console. Never set in production |
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
| `OSTROM_LANGFUSE_HOST`, `_PUBLIC_KEY`, `_SECRET_KEY` | — | Model calls to Langfuse (the three together); read at start, before the database, as `OTEL_*` (keep the secret key in a Secret) |
| `OSTROM_POSTHOG_API_KEY`, `OSTROM_POSTHOG_HOST` | —, EU cloud | The PostHog exporter ostrom-core builds (the server itself sends no product event); read at start, before the database, as `OTEL_*` |
| `OSTROM_POD_NAME` | random | `service.instance.id` (`ServerConfig.instanceId`; the chart sets it from the pod name) |
| `OSTROM_WEBHOOKS_ALLOW_PRIVATE_TARGETS` | `false` | `true` lets outgoing webhooks reach loopback and private addresses (an internal receiver); link-local, unspecified and metadata addresses stay refused |
| `OSTROM_CONSOLE_URL` | `https://app.ostrom.run` | The console, which every module reads: billing's Checkout and portal return pages and limit messages, GitHub's check links and install returns, the approval e-mails' links. The GitHub integration and the notifications still read the former `OSTROM_WEB_ORIGIN` while it alone is set, with a deprecation warning at start (the other modules never read it) |

A module that needs its own keys reads them from the `ConfigSource` bound in Koin (`get<ConfigSource>()`: the
environment, then `OSTROM_CONFIG_FILE`), with the `OSTROM_<MODULE>_…` prefix, and adds them to this table. In a
`serverTest`, give it values with `overrides = module { single<ConfigSource> { MapConfigSource(mapOf(…)) } }`.

### Settings in the console

The environment holds only what the server needs to start (the table above: address, database, keys, public URLs,
proxies, per-replica ceilings, and where its telemetry goes: OpenTelemetry, Langfuse and PostHog). Everything else is set by an administrator of the instance in the
console (**Admin → Settings**, the `admin` tag of the contract) and stored in PostgreSQL by the `admin` module, as TeamCode
keeps its dashboard settings: plain values in `admin_settings.plain_values`, secrets sealed with the secrets key and
bound to their section and field (`instance-setting:<section>:<field>`), never returned, only whether they are set.
Every save is checked against the version read (a `409 settings_conflict` when another came first) and written to the
instance's audit chain with the names of the fields it changed, never a value. A replica reads a section at most every
30 seconds; the replica that saved it at once.

A module declares a section by binding a `SettingsSection` in Koin (`kernel/settings`), and reads it through
`InstanceSettings.read(section)`. A field that replaces a former variable names it (`legacyVariable`): at the first
start where nothing is stored for the section, the variables set are imported once (secrets sealed), with a warning
that they are deprecated; afterwards a variable still set is ignored with a warning. A former variable `X` is also
read from the file `X_FILE` names (a mounted Secret); a missing file stops the start. The pull request that moves a
setting removes its variable from the table above and from the chart once the import has shipped.

The administrators of the instance (**Admin → Administrators**) are stored too: the
person who used the setup link is the first, and an administrator adds another by a verified e-mail of their account
or removes one (never the last). Only a person signed in (a browser session or the unlocked app) who administers the
instance reaches the admin routes, never a key whatever its scopes. `GET /auth/me` answers `instanceAdmin: true` for
them. `OSTROM_INSTANCE_ADMINS` is deprecated: its user ids are imported once, and a person an administrator removed is
never brought back by it.

The telemetry destinations stay in the environment because the server needs them before it reads any setting: it
starts its telemetry (ostrom-core's `Telemetry.start`, from the app's `startTelemetry`) before it connects to the database
and builds its modules, so that the start itself, a failed connection or a failed migration are traced and logged, and
the exporters are built once, from `TelemetrySettings`, for the life of the process. Langfuse receives the model calls'
spans through that same telemetry. They are an operator's observability, as `OTEL_*` is, never a product setting; the
server itself sends no product event to PostHog (`OSTROM_POSTHOG_*` only configures the exporter ostrom-core builds).

What lives in the console so far:

| Section | Moved from | Since |
|---|---|---|
| Administrators | `OSTROM_INSTANCE_ADMINS` | the `admin` module |
| Cloud runtime (on or off, runner delivery image and binary, callback URL, retention, per-member limits, transfers) | `OSTROM_RUNNERS_DRIVER`, `OSTROM_RUNNERS_IMAGE`, `OSTROM_RUNNERS_SERVER_URL` | the `cloud` module |
| Cloud clusters (API URL, sealed CA bundle and service-account token, allowed namespaces, pod ceiling) | — | the `cloud` module |
| Cloud access (`cloud-access`): the organizations cloud sessions are open to, `*` or organization ids separated by commas, stored plain (not a secret). None while unset, whatever their plan (audit S2-10: closed by default on a shared cluster); a change applies within 30 seconds, without a restart | `OSTROM_RUNNERS_ALLOWED_ORGS` | the `runners` module |
| Compiler settings, per organization (an owner or an admin, in the console, audited): `auto` or `manual` pins, the code publishers (absent: every person who decides; empty: nobody) and the production approver, read at each compilation, with an optimistic save. The variables, one policy for every organization, are deprecated: until an organization saves its own they stay its default (an upgrade never loosens a policy), with a warning at start | `OSTROM_PIN`, `OSTROM_CODE_PUBLISHERS`, `OSTROM_PROD_APPROVER` | the `compiler` module |
| Sign-up (`sign-up`): `invite`, the default (the setup link of `ostrom-server bootstrap`, an invitation or an organization's SSO only; only owners and admins, or the setup link's person, create organizations), or `open` (anyone who signs in, who may create organizations: ostrom.run). Serve logs a warning while an invite-only instance has no account | `OSTROM_SIGNUP` | the `identity` module |
| Sign-in with Google (`google`): the OAuth client, its secret sealed, and Google's issuer, consent page and token endpoint, each replaced alone (a fake Google in end-to-end tests). Offered once the client id and secret are set, without a restart | `OSTROM_GOOGLE_CLIENT_ID`, `OSTROM_GOOGLE_CLIENT_SECRET`, `OSTROM_GOOGLE_ISSUER`, `OSTROM_GOOGLE_AUTHORIZATION_URL`, `OSTROM_GOOGLE_TOKEN_URL` | the `identity` module |
| Sign-in with GitHub (`github-sign-in`): the OAuth client people sign in with (and `ostrom login` checks a GitHub token against), its secret sealed, GitHub's web and API URLs (GitHub Enterprise Server, a fake GitHub). Offered once the client id and secret are set | `OSTROM_GITHUB_CLIENT_ID`, `OSTROM_GITHUB_CLIENT_SECRET`, `OSTROM_GITHUB_WEB_URL`, `OSTROM_GITHUB_API_URL` | the `identity` module |
| GitHub (`github`: API URL, web URL, the name of the App the manifest flow creates) | `OSTROM_GITHUB_API`, `OSTROM_GITHUB_WEB`, `OSTROM_GITHUB_APP_NAME` | the `integrations-github` module |
| GitHub App (id, slug, sealed private key, webhook secret and OAuth client secret, OAuth client id; created by the manifest flow on **Admin → GitHub**) | `OSTROM_GITHUB_APP_ID`, `OSTROM_GITHUB_APP_SLUG`, `OSTROM_GITHUB_APP_PEM`, `OSTROM_GITHUB_WEBHOOK_SECRET`, `OSTROM_GITHUB_APP_CLIENT_ID`, `OSTROM_GITHUB_APP_CLIENT_SECRET` | the `integrations-github` module |
| Mail (`mail`): Resend's API key, sealed, and the sender (`Ostrom <login@mail.ostrom.run>` while unset). Sign-in links, invitations and approval e-mails go once the key is set, and e-mailed sign-in is offered then, without a restart. A developer's machine or an end-to-end test writes them to `OSTROM_MAIL_DIR` instead, which stays in the environment | `OSTROM_RESEND_API_KEY`, `OSTROM_MAIL_FROM` | the `identity` module |
| Usage (`usage`: days the ledger keeps its events, 1 to 3650, 400 by default), read at each hourly purge | `OSTROM_USAGE_RETENTION_DAYS` | the `usage` module |
| Stripe (`stripe`: the sealed secret key and webhook signing secrets, Stripe's API), read at each use; billing is on while the key and a webhook secret are stored | `OSTROM_STRIPE_SECRET_KEY`, `OSTROM_STRIPE_WEBHOOK_SECRET` (or their `_FILE`), `OSTROM_STRIPE_API_BASE` | the `billing` module |
| Gateway (`gateway`: the instance's sealed Anthropic, OpenAI and OpenRouter keys, the providers' base URLs), read at each model call | `OSTROM_GATEWAY_ANTHROPIC_API_KEY`, `OSTROM_GATEWAY_OPENAI_API_KEY`, `OSTROM_GATEWAY_OPENROUTER_API_KEY`, `OSTROM_GATEWAY_ANTHROPIC_URL`, `OSTROM_GATEWAY_OPENAI_URL`, `OSTROM_GATEWAY_OPENROUTER_URL`, `OSTROM_GATEWAY_CODEX_URL` | the `gateway` module |
| Sharing (`sharing`: the providers' OAuth token endpoints), read at each refresh | `OSTROM_SHARING_CLAUDE_TOKEN_URL`, `OSTROM_SHARING_CODEX_TOKEN_URL` | the `sharing` module |
| Push (`push`): `provider` (`off` by default, `relay` or `firebase`), the relay's URL (`relayUrl`, `https://push.ostrom.run` when unset) and the Firebase project's service account JSON key (`firebaseServiceAccount`, sealed) | new, never a variable | FCM push (`notifications`) |
| License (`license`: the sealed enterprise license key, verified on save and at start; a key that does not verify is never stored, and one that stops verifying grants nothing, with its reason logged) | `OSTROM_LICENSE_KEY` (or its `_FILE`) | the `license` module (`ee/`) |

The GitHub App is kept as TeamCode keeps it, in its own table (`github_apps`, one row, the key and secrets sealed),
not in a settings section: the manifest flow creates it once, and the stored App is the only one the server uses. At
start, when no App is stored and the former variables name a complete one (an id, a slug and a private key), it is
imported once, with a warning that the variables are deprecated; afterwards they are ignored with a warning. GitHub's
address is read again every 30 seconds, so a change in the console reaches every replica within a minute.

The `cloud` module follows TeamCode's cloud model. The instance's administrators set the runtime and connect clusters:
a cluster's API URL is an https URL that is never a link-local, unspecified or metadata address in any numeric
spelling, its CA bundle and token are sealed (`cloud-cluster:<id>:ca|token`) and never returned, and a changed URL
needs both again. Turning the runtime off refuses only new sessions. Each organization's owners and admins publish
versioned cloud profiles (cluster, namespace, the team's own session image, allowed image overrides, pull secrets,
resources, idle timeouts) and assign them to members; a published version never changes. Every save is optimistic (a
`409` when another came first) and audited without a credential.

#### Push notifications

Ported from TeamCode's docs/notifications.md ("Push"). The phone app registers its FCM registration token (iOS and
Android alike) with its P-256 public key (`POST /api/v1/push-devices`, `provider: fcm`, `publicKey` in
SubjectPublicKeyInfo DER, base64), at every start and when FCM rotates the token. Each push is end-to-end encrypted
for that key (ECIES: an ephemeral P-256 key, HKDF-SHA256 with info `ostrom-push-v1`, AES-256-GCM; the contract's
`PushPayload`), so the provider sees a token and an opaque blob, and the text exists in clear only on the server and
the phone. The FCM message carries `{"p": <payload>, "v": "1"}`: on iOS a generic alert with `mutable-content` the app's
notification service extension replaces, on Android a high-priority data message, both with an opaque collapse key and a
one-hour expiry. The server signs its own OAuth assertion with the service account (RS256) and keeps the access token
until a minute before it expires. A token FCM no longer knows (404 `UNREGISTERED`) forgets its phone, unless the app
registered it anew; a 401, 408, 429, 5xx or no answer is tried again; any other refusal, or `firebase` without its key,
is given up at once. While the provider is `off`, phones register and nothing is sent. Expo is gone, as in TeamCode: a
`provider: expo` registration is refused (400), and a phone an older app registered with Expo is never notified (it has
no key to encrypt for) until the app registers FCM. The former Expo variables are no longer read: a deployment that
still sets them can drop them.

Two providers send the same FCM message. `firebase` is the instance's own Firebase project, for apps it builds and
signs itself. `relay` is the Ostrom Push relay (`push/`), which holds the Firebase project of the published apps
(TeamCode's relay, protocol `push.v1`, the contract's `PushRelay`): its URL is `https`, a host and nothing after it,
never a link-local or cloud metadata address, and every call to it goes through the kernel's outbound client. At the
first push through a relay the server makes an Ed25519 key pair shared by every replica
(`notifications_push_relay_instances`, the private key sealed with the secrets key, context `push-relay:<url>`),
registers its raw public key (`POST /v1/instances`) and signs every send (`Ostrom-Instance`, `Ostrom-Timestamp`,
`Ostrom-Signature` over `POST\n/v1/send\n<timestamp>\n<body sha256>`). The relay's `sent` delivers, `unregistered`
forgets the token, `retry`, 408, 429, 5xx or no answer are tried again, a 401 (the relay no longer knows the key)
registers the same key again at the next push, a 403 (the instance is blocked) and any other refusal are given up.

### Database connections

One replica opens at most `OSTROM_DATABASE_MAX_CONNECTIONS` connections (10 by default), plus one that listens
(`DatabaseNotifications`). The bounds are TeamCode's (`TEAMCODE_DATABASE_POOL_SIZE`, 2 to 64), with a floor of 4 while
two pools share the number: until the last module leaves sqlx4k (JVM step 4), the JDBC pool (HikariCP, Exposed, `/ready`)
takes the larger half and sqlx4k's pool the smaller (5 and 5 by default, 6 and 5 for 11), each at least 2. Once sqlx4k
is gone, the JDBC pool takes them all and the floor goes back to 2. The default stays 10, not TeamCode's 16: with the
chart's `max_connections` 200 and 10 replicas at most, 16 would not fit the budget below. It is read from the
environment, not the console, because the pool opens before any setting can be read from the database.

The budget across replicas (CRL-01), to keep at or under 80 % of PostgreSQL's `max_connections`:

```
(most replicas + rolling surge) × (OSTROM_DATABASE_MAX_CONNECTIONS + 1 listening)
  + 2 (ostrom-server migrate, Flyway's pool, in the init container)
  + Ory Polis's pool (when enabled) + 1 backup (pg_dump) + 1 metrics exporter + 3 reserved (an operator's psql)
  ≤ 0.8 × max_connections
```

With the chart's defaults (an HPA of at most 10 replicas, a surge of 1, Polis off, `max_connections` 200):
11 × (10 + 1) + 2 + 1 + 1 + 3 = 128 ≤ 160. Above 10 replicas or 12 connections each, raise `max_connections`
first. The chart refuses to render values over this budget with its bundled PostgreSQL (ostrom-helm#53).

### Memory under the container limit

The server reads its container's memory limit (cgroup v2 `memory.max`, else v1) and holds itself under it, with no
key to set (the ceilings of calls can be set):

- The JVM's heap follows the limit: the image sets `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70
  -XX:+ExitOnOutOfMemoryError` (TeamCode's), so the heap is 70 % of the limit and an `OutOfMemoryError` ends the
  process, which the orchestrator restarts. The image's smoke test checks the heap against a 1 GiB limit. The
  ceilings below were measured on the earlier Kotlin/Native binary (docs/decisions/0001) and are re-sized once the
  JVM is measured under the chart's load.

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

The inference gateway reads the instance's providers in the console (**Admin → Settings → gateway**) at each model
call. An instance key serves every org of the instance and comes before an org's own key (`/api/v1/inference/settings`
names an org secret instead); organizations keep their own keys as before. The keys are sealed and never returned; a
URL is `http(s)` only. The former `OSTROM_GATEWAY_*` variable of each field is imported once.

| Field | Default | Meaning |
|---|---|---|
| `anthropicApiKey` | — | Anthropic API key for every org, when no shared Claude account serves |
| `openaiApiKey` | — | OpenAI API key for every org (Responses, Chat Completions) |
| `openrouterApiKey` | — | OpenRouter key for every org (Chat Completions with a `vendor/model` name) |
| `anthropicUrl` | `https://api.anthropic.com` | Anthropic base URL (`/v1/messages` is added) |
| `openaiUrl` | `https://api.openai.com` | OpenAI base URL (`/v1/responses`, `/v1/chat/completions`) |
| `openrouterUrl` | `https://openrouter.ai/api` | OpenRouter base URL (`/v1/chat/completions`) |
| `codexUrl` | `https://chatgpt.com/backend-api/codex` | ChatGPT's Codex backend (`/responses`), for shared ChatGPT accounts |

A shared account that refuses a call before any output hands it to another, three accounts at most. How far it goes
depends on the refusal (TeamCode #226): an account that cannot serve (a 401, or a 429 whose quota headers show a spent
subscription window) sends the call on to the next account; a plain 429, a rate limit that may hold for every account,
switches account once only, and a second one answers 429 `provider_rate_limited` with the shortest `Retry-After` the
provider gave. Either way the account rests as after any 429.

A model call waits for its turn in the org's admission queue (TeamCode's admission), shared by every replica in
PostgreSQL (`gateway_admissions`, `gateway_admission_turns`, under an advisory lock of the org): while every shared
account it may use is busy, or while its member already runs as many calls as their limit (`memberConcurrency` in
the inference settings, 0 to 32; 0, the default, is unlimited, as TeamCode). Interactive calls go first and a
background one (`x-ostrom-workload: background`) joins them after 30 s; then the member served longest ago, then
arrival order. Those turns are taken only while a waiting call's member has a limit: otherwise every waiting call
tries the accounts as it polls, and a free account takes them all in one round (TeamCode #87). A call waits 90 s at
most (503 `queue_timeout`); 128 calls wait per org and 8 per limited member (503 `queue_full`, checked as a call
arrives, whether it waits or not); a call whose replica stops
polling loses its turn within 5 s and its place within 15 s, and a running call's place lasts 15 minutes, renewed
every 5. Waiting holds no account, no budget and no database connection. The inference API's queue route counts the
waiting calls; every gateway refusal carries its code in `x-ostrom-error`. A refusal retrying does not change also sends
`x-should-retry: false`, as TeamCode does (e7dbca54): a spent budget (`budget_exhausted`, `budget_insufficient`), an
invalid request, credential or model, no account nor key (`no_credential`), and any refusal whose `retry-after` is
more than two minutes away. Claude Code and the Anthropic SDKs then show the cause at once instead of retrying a
state only a new period or an admin changes.

A call sent with `x-ostrom-client-request-id` (a lowercase UUID) can be stopped by its member, on whichever replica
runs it, through the inference API's cancel route (TeamCode's `requests.cancel.v1`): a marker in
`gateway_cancellations`, kept 10 minutes (256 per member, 4,096 per org, then 503 `cancellation_limit`), read before
the call starts and every 500 ms while it waits or streams. A call without that header reads no marker.

### Module `governance`

A governance write (the rules, the gate policy, the model policy, a workflow's decision) made with a person's API
token, the CLI's (elevated by `ostrom login --admin` or not) or a personal one, is not applied: the route answers 202
with a confirmation that an owner or an admin answers on the console, at
`OSTROM_CONSOLE_URL/confirm/<id>` (audit CLI H1: an agent on the developer's machine can present the developer's
tokens). A confirmation shows what the write replaces and what it writes, lives 15 minutes, is answered once, and
each step (created, confirmed, refused, expired, failed) is an event of the organization's signed audit log. While a
confirmed write is being made it still reads `pending`, and a waiting read goes on waiting: the CLI reads it once
with its result (who was invited), never confirmed with none. One still confirmed with no result 5 minutes past its expiry (its server stopped
between the answer and the result) is failed by the sweep with `result_lost`, which the console and the CLI show:
never pending for ever (decision of 14/11/2026). A token never answers; a browser session and an `inference:admin` service key still write at once. A module that owns a
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
(`sharing_slots`, `sharing_affinities`), so an account's `maxConcurrent` (0, the default, for unlimited, as TeamCode) holds across every replica and a session keeps
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

`GET /api/v1/accounts` lists the accounts whose quota resets latest first (TeamCode #147/#149): an account's next
reset is its weekly window's while that is still ahead, otherwise the soonest still ahead among its other windows (a
model's own window never counts, since it never bars the account). Accounts with no known reset come last, in priority
order; a member's neutral `Account N` labels keep the priority order, not the listing's.

Refresh ownership follows TeamCode's (`docs/capacity.md` « Refresh ownership » there, `sharing.handover.v1`): a share
from a sign-in is Ostrom's at once, since nothing else holds its refresh token. An account the TeamCode import creates
waits for a handover (`refreshOwnership` `pending_handover`, status `pending_handover`): TeamCode may still renew its
tokens, and two refreshers of one rotating refresh token disconnect the account, so it serves nothing and no replica
leases it. Each later import gives it TeamCode's current tokens. Once TeamCode no longer uses it, an admin hands it
over (the account's handover route, or the import's cutover run, which hands over every waiting account with
TeamCode's tokens), and Ostrom renews it from then on. As TeamCode's `accounts handover ID --confirm`, the handover
route also takes an administrator signed in on the CLI: the token of `ostrom login --admin` holds `accounts:read` and
`accounts:write` (`ostrom accounts handover <name> --confirm`), the role is checked again before the write, and the
write is recorded in the audit log (`governance.write`) first; every other account change stays a console write. The import's `accessOnly` trial (TeamCode's `--access-only`),
or the account's access-only route, serves an account with its access token and never renews it: once the token is within the 5-minute margin of its expiry, the account serves nothing until imported
again. Pausing and resuming never change the ownership; a member's own share of a waiting or access-only account is
refused (403 `account_in_use`), an admin's takes it over. A handover is refused (409 `account_refreshing`) while a
refresh intent is on the row, in flight or left by a failure: new tokens clear it first.

The providers' token endpoints are the instance's, set in the console (**Admin → Settings → sharing**) and read at each
refresh: https, or http to a local host only (`OSTROM_SHARING_CLAUDE_TOKEN_URL` and `OSTROM_SHARING_CODEX_TOKEN_URL`
are imported once).

| Field | Default | Meaning |
|---|---|---|
| `claudeTokenUrl` | `https://platform.claude.com/v1/oauth/token` | Token endpoint of shared Claude accounts |
| `codexTokenUrl` | `https://auth.openai.com/oauth/token` | Token endpoint of shared ChatGPT (Codex) accounts |

### Module `skills`

The organization's skill catalog, ported from TeamCode (`docs/skills.md`, and #268 for personal skills). A version is
immutable: its files (1 to 200, at most 5 MiB, `SKILL.md` at the root, stored in PostgreSQL) and its digest never
change, and the same label published again answers 200 `created: false` with the same content, 409
`skill_version_conflict` with another. The digest is `sha256:` over one line per file sorted by path,
`<path>\0<mode>\0<size>\0<sha256 hex>\n`, which the CLI recomputes before it installs anything.

Only the pinned version is delivered, to every member (`allMembers`) or to the members set to yes; an explicit no
always wins. Owners and admins manage the catalog from the console only (a browser session). A CI publishes with a
service key holding `skills:publish`, which pins with `skills:pin` and opens to all members with `skills:assign`
(reserved scopes: the key works only while its creator is an owner or an admin); each of its changes is audited with
the key's id. A member publishes personal skills, which only they receive, from the console or the CLI
(`skills:write`); an owner or an admin promotes one to the team, which clears its owner, its pin and its audience.
A skill name is unique in the organization, team and personal alike. Limits: 500 team skills, 50 personal skills per
member, 200 versions per skill.

Every write takes the organization's catalog lock (a transaction-scoped advisory lock), so writes of one organization
run one after the other on any replica, and the audit events are written once the change committed. The CLI's token
is the workstation (`GET /api/v1/devices`): it alone reports what it installed, a report never grants nor revokes
anything, and the rows go with the skill.

A cloud session receives its owner's skills too (TeamCode's cloud agent, `cli/cloud/agent/Skills.kt`): the runner
asks with its runner token and its execution (`sessionId`, `epoch`), fenced as its other calls, and gets the owner's
manifest and artifacts; a session that holds no execution gets 409 `delivery_unavailable`, an owner who left gets
nothing.

### Module `hooks`

The organization's team hooks, ported from TeamCode (`docs/hooks.md`). A hook runs code on members' computers with
their rights, so it reaches them through three locks: a CI publishes versions with a service key holding
`hooks:publish` (a reserved scope, created by an owner or an admin), but only an owner or an admin pins a version, from
the console; only members set to yes receive it, or every member not set to no when its audience is all members; and
each member approves each version on their computer before the CLI installs it. A publication never pins, and a key
asks for all members only while nothing is pinned or when the hook already is for all members (403
`hook_audience_requires_administrator` otherwise, nothing stored): the administrator's pin approves the code and who
runs it together.

A version is immutable: its definition (`events` among Claude Code's, each once; a `matcher` of 1 to 256 characters
without control characters; a `timeoutSeconds` of 1 to 600, 60 by default; `command`, an executable file of the
artifact) and its files (1 to 50, at most 1 MiB, paths as the skills', stored in PostgreSQL) never change. The digest
is `sha256:` over `hook\0<events sorted, comma-separated>\0<matcher or empty>\0<command>\0<timeout>\n` followed
by the skills' file lines, so a client verifies what it will run and when. The same label published again answers 200
`created: false` with the same digest, 409 `hook_version_conflict` with another. Limits: 100 hooks, 200 versions per
hook. An administrator reads any version's code before pinning it (`readHookVersion`);
a member downloads only their effective pinned version, and both are checked against the digest (500
`hook_integrity`). There is no retirement and no personal hook, as in TeamCode. The catalog's writes take the
organization's hook lock, as the skills' do, and each change is audited once committed, a key's with its id.

A cloud session receives its owner's delivered hooks as it receives skills, with no member approval: the pod is the
team's infrastructure, and the administrator's pin and the owner's yes approve it (TeamCode's `docs/hooks.md`, Cloud
sessions).

### Module `memory`

The agent memory (TeamCode's docs/memory.md): short entries agents and people read on demand, the organization's
(`team`) and each member's own (`personal`). Visibility is a parameter of every query (`org_id`, `scope`, `owner_id`),
never a filter applied after the read: a personal entry is its owner's alone, administrators included, and any other
caller gets the 404 of an unknown entry. A person (browser, phone, the CLI's sign-in) or a credential holding
`memory:use` (agent tokens are issued with it) reads and writes. Only a person writes or changes the organization's
entries, as their author or as an administrator. An agent's call, an agent token or a request the CLI's relay
forwards with `x-ostrom-origin: agent`, writes its personal entries directly; its organization entry is a proposal
(TeamCode #272), a key's is refused (403). A proposal names the agent's session (`sessionId`, 400
`memory_proposal_session` without it; an agent token's must be its launch's, a relayed call's its person's) and is
stored `proposed`, read by nobody, while the session's person is asked an ordinary wait with the entry to edit (at most
20 proposals of a member wait, 409 `memory_proposals_limit`). The settling pass (`memory/settle`, every 10 s on the
instance's `ScheduledWork` lease, one replica at a time) applies the answers, the person's valid edits included, rejects
what nobody answered in 14 days, and deletes the rejected entries after 30 days. In the queue
(`GET /api/v1/memory/proposals`, coming soon in the console) an administrator accepts or rejects any proposal, a
member rejects their own. Text
that looks like a credential is refused (422 `memory_secret`). A memory holds 5 000 organization entries and 2 000 per
member. The index and the search never read `content`; the audit names ids only.

The curation (TeamCode #273, coming soon in the console) is off for an organization until an
administrator turns it on (`PUT /api/v1/memory/curation`, a person's, audited `memory.curation_enabled` or
`memory.curation_disabled`); the setting is a row of `memory_settings`, never an environment variable. Every hour
(`memory/curate`, on the same lease, 4 minutes at most), a run reads at most 20 scopes of the organizations that turned
it on (the organization's entries, or one member's personal entries alone), each changed since its last pass or curated
over a week ago, one per transaction. It never calls a model: entries whose title is equal once case and spaces are
ignored form a group (20 a scope, 8 entries each); the older of two with equal text is superseded by the newer
(`superseded_by_id`, nothing deleted, audited `memory.superseded` with the run's id); a pair with different text, a
person's older entry against an agent's newer one, or an entry tagged `external`, goes to a review queue
(`GET /api/v1/memory/reviews`, `POST …/reviews/{id}/apply` or `/dismiss`) instead. A superseded entry is listed
(`GET /api/v1/memory/superseded`) and restored (`POST /api/v1/memory/{id}/restore`, audited `memory.restored`) by its
owner, or for the organization's by its author or an administrator; the entry that replaced it becomes the superseded
one. These routes are a person's only. The retention runs for every organization, curation on or off: an entry
expired for 7 days is deleted (audited `memory.expired`), a superseded one 90 days after, an answered review 30 days
after. The run finds the organizations through `memory_kept_orgs` (docs/tenancy.md).

## Commands

| Command | What it does | Exit |
|---|---|---|
| `ostrom-server` (or `serve`) | Refuses a database role that is a superuser, has `BYPASSRLS`, or owns a table or is a member of its owner, checks the migrations, serves. On SIGTERM or SIGINT: `/ready` at 503 for `OSTROM_SHUTDOWN_DELAY_SECONDS`, then the calls in flight end (up to `OSTROM_SHUTDOWN_GRACE_SECONDS`), then the engine stops and the pool closes | 0 after a graceful stop, 1 on a failure, 2 on an invalid configuration |
| `ostrom-server migrate` | Applies every module's migrations with `OSTROM_DATABASE_MIGRATION_URL`, under the advisory lock | 0, 1 on a failure, 2 without the URL |
| `ostrom-server bootstrap` | While the instance has no account, prints the first owner's setup link (`<OSTROM_CONSOLE_URL>/setup#token=osb_…`): it works once, for an hour, and a new one stops the earlier one. The person opens it and signs in with GitHub, Google or an e-mail link. The link is printed on the command's output only, never in the logs | 0, 2 when the instance already has an account or no module makes setup links |
| `ostrom-server seed-test-account <email>` | With `OSTROM_ALLOW_TEST_ACCOUNTS=true`, creates a test account through the module that binds `TestAccountSeeder` (identity) and prints `{"email","token","organizationId"}` on one line | 0, 2 when refused or when no module binds the seeder |

The probes: `GET /health` (liveness: 200 `ok` while the process answers HTTP, with no check, also while draining and
during a database outage, as TeamCode's, audit SD-04) and `GET /ready` (readiness, 200 `ready`, 503 `degraded` or
`draining`), both unauthenticated. `/ready` reads the JDBC pool's state without any query (CRL-11): it is `degraded` when
the pool is closed, or when callers have waited for a connection for 30 s without a break, so a short database stall
gives slow answers instead of every replica leaving the Service at once.

`GET /api/v1/capabilities` (`Capabilities` and `ServerFeatures` in ostrom-core), served by the kernel, answers what
clients read after signing in: `apiVersion` (the contract version of the ostrom-core the server is built on,
`ServerFeatures.API_VERSION`), `serverVersion` (the build's `ostromVersion`, `0.0.0-dev` outside CI) and `features`, the
sorted union of every module's `ServerModule.features(koin)`. Any credential reads it, with no organization or scope; an
anonymous call gets 401 `unauthorized`. A module lists a key of ostrom-core's `ServerFeatures` only for what it serves
**and** what is configured on this instance, never for a planned operation:

| Module | Keys | Condition |
|---|---|---|
| `admin` | `admin.settings.v1` | — |
| `artifacts` | `artifacts.v1` | — |
| `automations` | `automations.v1`, `waits.v1` | — |
| `billing` | `billing.v1` | A Stripe account stored in the console's `stripe` settings (key and webhook secret) |
| `budgets` | `budgets.v1` | — |
| `cloud` | `cloud.admin.v1` | — |
| `compiler` | `packages.v1`, `workflows.v1` | — |
| `events` | `events.v1`, `webhooks.v1` | — |
| `gates` | `gates.v1` | — |
| `gateway` | `inference.v1`, `inference.queue.v1`, `inference.cancel.v1` | — (an organization can set its own provider keys) |
| `governance` | `governance.v1` | — |
| `hooks` | `hooks.v1`; `runners.cloud.hooks.v1` | the second with a runtime driver (`OSTROM_RUNNERS_DRIVER` not `none`) |
| `identity` | `audit.v1` | — |
| `import` | `import.teamcode.v1` | — |
| `integrations-github` | `github.v1`; `github.link.v1` | a stored GitHub App (created by the manifest flow, or imported once from the former variables); the second once that App's OAuth client (id and secret) is known |
| `mcp-relay` | `mcp.v1`, `mcp.personal.v1` (members' own servers) | — |
| `memory` | `memory.v1` | — |
| `notifications` | `notifications.v1`, `notifications.push.v1` | — (phones register their FCM token and P-256 key; pushes go out once an administrator picks the provider in Admin → Settings → push.) |
| `receipts` | `receipts.v1` | — |
| `reports` | `reports.v1` | — |
| `rules` | `rules.v1`, `reports.v1` (the analysis) | — |
| `runners` | `runners.v1`; `runners.cloud.v1`; `runners.cloud.github.v1`; `runners.cloud.variables.v1`; `runners.cloud.environment.v1` | the second with a runtime driver (`OSTROM_RUNNERS_DRIVER` not `none`); the third also once members can link a GitHub account (`github.link.v1`); the fourth also with the secrets module (`environment.v1`); the fifth also with the orgs module, which says who is an active member |
| `secrets` | `secrets.v1`, `environment.v1` (environment variants) | — |
| `sessions` | `sessions.v1` | — |
| `sharing` | `sharing.v1` | — |
| `skills` | `skills.v1`; `runners.cloud.skills.v1` | the second with a runtime driver (`OSTROM_RUNNERS_DRIVER` not `none`) |
| `todos` | `todos.v1` | — |
| `usage` | `usage.v1` | — |
| `workflow-runs` | `workflows.v1`, `notifications.v1` (the hooks' notifications) | — |

`slack.v1` and `license.v1` are not listed: the Slack module is an empty skeleton and the `ee/` license module serves
no route yet. A module that adds a feature, or a route behind a configuration, updates its `features` and this table.

The database roles of a deployment (the Helm chart creates them): an **owner**, which owns the schema and runs
`migrate`, and a **serving role** that owns nothing, `NOSUPERUSER NOBYPASSRLS`, granted `USAGE` on the schema and,
through `ALTER DEFAULT PRIVILEGES FOR ROLE <owner>`, `SELECT, INSERT, UPDATE, DELETE` on its tables and `USAGE, SELECT`
on its sequences. CI's smoke test sets them up the same way. The migrations' history tables (`flyway_<name>`, and
the older `_migrations_<name>`) are the exception: after each pass, `migrate` revokes every privilege but `SELECT` on them from every role but the
owner, so the serving role reads the history (the start-up check) and never rewrites it.

## Known limits of the foundation

- **The in-memory limits are per replica**: the read limits (`OSTROM_READS_*`, `OSTROM_READ_WAITS_IN_FLIGHT`) count
  each pod apart, as a protection of its own CPU and memory, the one kind of state TeamCode also keeps in a process
  (docs/replicas.md: "local CPU-concurrency protection"). The events a member declares in a minute
  (`POST /api/v1/usage/events`, 6 000 at most) are not among them: their count is a row of `usage_ingest_minutes`,
  counted with the batch's write in one transaction, under the row's lock and by the database's clock. The sign-in rate limits are not among them: their windows
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
- **The event feed holds across replicas**: every module publishes to the organization's journal
  (`events_journal`, kernel `OrgEventPublisher`, preferably in the transaction of the change it reports) under the
  organization's row of `events_cursors`, so the sequence has no gap and commit order is sequence order; each open
  stream of the feed (`events.v1`) reads the journal every second on its own replica, so an event published on one
  replica reaches the streams of all of them within a poll. Events are kept 7 days. The outgoing webhooks
  (`webhooks.v1`) hold across replicas too: each event is queued once per webhook (unique in
  `events_webhook_deliveries`), and every replica's worker claims due deliveries under a one-minute lease, so a
  crashed replica's claims come back to the others. They are sent only through the kernel's outbound client.
  A module that turns the journal's events into its own work after they commit (the notification router) reads it
  through the kernel's `OrgEventJournal`: one cursor per reader and organization (`events_readers`), moved in the
  reader's own transaction with the work it writes and guarded by its previous value, so two replicas never handle a
  page twice. The waits an event names are read through the kernel's `WaitSummaries` (automations module).
  The gates publish theirs in the gate's own transaction: `cloud.approval.requested` when an enforced gate asks its
  approvers and `cloud.approval.decided` when it is answered, one per approval request with its own status, events of
  the whole organization naming the approver's login (no other module is read inside the gate's transaction), never
  the gate's label or evidence.
  The notifications module holds the router's rules (`NotificationRules`: a wait a person resolves tells its
  assignee, a failed turn's wait its owner), each member's switches per channel and kind
  (`notifications_member_settings`, contract `getNotificationSettings`, no row meaning on) and the channels the server
  offers: every `NotificationChannel` bound in Koin. Its push channel (`push`) reaches a member's `fcm` phones that
  registered a key and whose credential still works, through the provider of the instance's `push` settings (nobody
  while it is `off`); `sendTestPush` sends a member a test now, once every ten seconds per
  member on each replica.
- **A cloud session's terminal is relayed by the replica holding its runner's socket** (TC-REP-1). That replica
  records the hold in `runners_terminal_hosts` (renewed every 30 s, 90 s lease by the database's clock, the newest
  execution wins). A member attached on another replica is relayed to it as a hop client, at its pod address
  (`OSTROM_RUNNERS_REPLICA_URL`), on a single-use ticket of theirs. When the runner moves, the member hears
  `{"type":"runner","connected":false}` and is attached where it now is, on the same socket. Their replica keeps
  where they are in the output (a cursor: the room, one stay of the terminal in a replica's memory, and its byte
  count, which a hop hears as `{"type":"joined"}` and the member never does) and comes back with it (`after=`), so
  only what they have not seen is sent, as TeamCode's attach resumes after its last event; from another room, or past
  the scrollback (TeamCode's `gap`), the terminal is reset (ESC c) before the scrollback, drawn again, never doubled.
  Unset (one replica), every terminal is served where it is attached.
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
