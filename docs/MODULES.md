# Adding a functional module

Every functional domain of the server (`identity`, `orgs`, `sessions`, `gates`, `receipts`, `compiler`,
`mcp-relay`, `secrets`, `usage`, `gateway`, `budgets`, `runners`, `automations`, `artifacts`, `integrations-github`,
`integrations-slack`, `sharing`) is a Gradle module under `modules/<name>/`. Each one already exists as an empty
skeleton, registered in the app: **the owner of a module only edits its own directory**, plus its single line in
`app/.../ServerModules.kt` if the order must change. `modules/instance` is the complete reference example: copy
its shape.

Read [`ostrom-core/docs/CONVENTIONS.md`](https://github.com/ostrom-hq/ostrom-core/blob/main/docs/CONVENTIONS.md)
first: it wins over this page wherever they differ.

## 1. Layout

```
modules/<name>/
  build.gradle.kts                      # plugins { id("ostrom.native-module") } + project(":kernel")
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
- **presentation** maps HTTP to use cases; routes receive a `<Name>RoutesDependencies` data class.
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
`TokenAuthenticator`, `MigrationRunner`, and the `HealthCheck`s. Use `Clock` from Koin, never `Clock.System`
directly, so tests can fix the time.

**Koin**: the app and the tests build Koin with `serverKoin(...)` (a local `koinApplication`, never `startKoin`,
never `install(Koin)`), **without overrides**: two modules binding the same type, or a module rebinding a kernel
type, fail the start. The one exception is the `TokenAuthenticator`: the identity module binds it
(`single<TokenAuthenticator> { … }`); until then `serverKoin` binds `RejectingTokenAuthenticator` and every protected
route answers 401. Only tests replace bindings, through `serverTest(overrides = …)`.

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
- An applied migration is never edited (its checksum is verified at each start and the server refuses to start);
  add a new version instead.
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
- `Uuid` (kotlin.uuid), `Instant` (kotlin.time), `String`, numbers, `Boolean`, `ByteArray` bind directly.

## 5. Routes and errors

```kotlin
@Serializable @Resource("/v1/orgs") class OrgsApi { @Serializable @Resource("{id}") class Id(val parent: OrgsApi = OrgsApi(), val id: String) }

fun Route.orgsRoutes(dependencies: OrgsRoutesDependencies) = with(dependencies) {
    authenticated {
        post<OrgsApi, CreateOrgRequest> { _, request ->
            val principal = call.requirePrincipal()
            call.respond(HttpStatusCode.Created, createOrg(principal.subjectId, request.name).toOrgResponse())
        }
    }
}
```

- Every route is under `/v1/…`, except `/health`.
- **Errors are thrown, never answered by hand**: a domain error subclasses `OstromException(ErrorKind.X, "message safe
  for the client")` (`class OrgNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Organization not found.")`).
  The kernel answers `{"error":{"code","message","requestId"}}` with the kind's status. Any other exception is a 500
  whose message is logged, never sent. A unique-constraint violation is a 409.
- Protected routes go in `authenticated { }`; `call.requirePrincipal()` gives the `AuthenticatedPrincipal`. Its
  `scopes` are explicit: an empty set grants no scope, never "everything".
- A body that is not JSON is a 415, a malformed one a 400.
- DTOs and `@Resource` classes are `@Serializable`; they move to `ostrom-core` (the API contract) as soon as it is
  published, and the web/app/cli clients use them from there.

## 6. Logs

Inject `Logger` and log events with constant messages and named fields:
`logger.info("org created", mapOf("org_id" to org.id))`. Never log a token, a secret, a password or a request body.
Every request is already logged once by the kernel (method, path, status, duration, request id).

## 7. Tests

- **TDD**: the failing test first, for every behaviour ported from the prototype (`~/git/ostrom/mvp/server`, reuse its
  test cases).
- **Unit tests** (use cases): hand-written fakes (`InMemoryOrgsRepository`); MockK does not exist on Kotlin/Native.
  Coroutines with `runTest`; time from an injected `Clock`.
- **Database and route tests**: `serverTest(listOf(OrgsModule)) { server -> server.client.get("/v1/orgs") … }` starts
  a fresh PostgreSQL database, applies the migrations, runs `initialize`, serves the routes, and drops the database
  afterwards. Fakes replace real bindings through `overrides = module { single<TokenAuthenticator> { … } }`.
  Name these classes `*PostgresTest`.
- Locally: `docker compose up -d postgres`, `export OSTROM_TEST_DATABASE_URL=postgresql://localhost:5432/postgres`,
  `./gradlew :modules:orgs:macosArm64Test` (through `.coord/build-lock.sh` during the night measurements). Without the
  variable, database tests are skipped locally; CI sets `OSTROM_TEST_DATABASE_REQUIRED=true` so they can never be
  skipped there.

## 8. Before the PR

- `./gradlew linuxX64Test` passes in CI (tests run on Linux in CI; macOS only locally).
- One PR per module step, small; the PR touches only `modules/<name>/` (and its line in `ServerModules.kt` if needed).
- `ee/` code: same shape, under `ee/modules/<name>/`, AGPL code never depends on it. The build does not include
  `ee/` yet: the first enterprise module adds its discovery and registration (see `ee/README.md`).

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `OSTROM_CONFIG_FILE` | — | Optional `KEY=value` file; the environment wins over it |
| `OSTROM_HOST` | `0.0.0.0` | Listen address |
| `OSTROM_PORT` | `8080` | HTTP port |
| `OSTROM_DATABASE_URL` | required | `postgresql://host:5432/db` (no credentials in the URL) |
| `OSTROM_DATABASE_USER` | required | Database user |
| `OSTROM_DATABASE_PASSWORD` | required | Database password |
| `OSTROM_DATABASE_MAX_CONNECTIONS` | `10` | Pool size |
| `OSTROM_SIGNING_KEY_FILE` | `data/instance-key.pem` | Ed25519 key (PKCS#8 PEM), created with mode 0600 at first start; keep it on a persistent volume |
| `OSTROM_LOG_LEVEL` | `info` | `debug`, `info`, `warn`, `error` |
| `OSTROM_SECRETS_KEY` | — | The secrets key, base64 of 32 bytes (AES-256-GCM); wins over the file. Losing it loses every stored secret |
| `OSTROM_SECRETS_KEY_FILE` | `data/secrets.key` | File of the secrets key, created with mode 0600 at first start when `OSTROM_SECRETS_KEY` is unset; keep it on a persistent volume |
| `OSTROM_USAGE_RETENTION_DAYS` | `400` | Days the usage ledger keeps its events (1 to 3650); older ones are purged every hour |

A module that needs its own keys reads them from the `ConfigSource` with the `OSTROM_<MODULE>_…` prefix and adds them
to this table.

## Known limits of the foundation

- **No graceful shutdown on SIGTERM yet**: the process stops without draining requests or closing the pool.
- **Migrations of several replicas starting at once are not serialized** (no advisory lock: the sqlx4k pool does not
  pin a connection for the migrator). Start one replica first after a release that adds migrations.
- **SQLite** (tests and local mode) is not wired yet: sqlx4k has a SQLite driver with the same `Driver` interface;
  repositories must stay portable SQL where they can.
- **The instance signer is provisional** (`kernel/signing`): it moves to `ostrom-core` once published; the
  `InstanceSigner` interface and the key file format (PKCS#8 PEM, `kid` = 16 hex of SHA-256 of the SPKI DER, as the
  prototype) stay.
- **API DTOs** live in each module until `ostrom-core` publishes the contract.
