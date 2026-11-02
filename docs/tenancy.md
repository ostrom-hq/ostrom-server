# Tenancy: how one organization never sees another's rows

The rule is CONVENTIONS section 16.1 (ostrom-core); this page says how `ostrom-server` applies it.

## The roles

- The **owner** owns the schema and every table. It is used only by `ostrom-server migrate`
  (`OSTROM_DATABASE_MIGRATION_URL`). The migration lock is a PostgreSQL advisory lock, waited for without a timeout:
  a second `migrate` waits for the first to finish.
- The **serving role** (`ostrom_app` in the Helm chart) owns nothing and is `NOSUPERUSER NOBYPASSRLS`: row-level
  security applies to it, `FORCE ROW LEVEL SECURITY` included. It can read and write rows, never alter a table, a
  policy or a function, nor create one.
- **`ostrom_tenant`** (`NOLOGIN`, owner of nothing) is the role the `inTenant` of some modules (secrets, usage,
  budgets, runners) switches to with `SET LOCAL ROLE`, until they move to the kernel's `withOrganization`, which needs
  no switch. The serving role must be a member of it, and it needs `USAGE` on the schema; their migrations create it
  when missing, so the owner has `CREATEROLE` (since PostgreSQL 16, that only lets it manage the roles it administers).
  `ci/database-roles.sql` creates it beforehand, as a deployment must.
- At start the server refuses to serve (`UnsafeDatabaseRoleException`, exit 1) when its role is a superuser, has
  `BYPASSRLS`, or owns a table of the schema or is a member of its owner, even without inheriting its rights
  (`pg_has_role(current_user, relowner, 'MEMBER')`): such a role could reach, or unlock, every organization's rows
  (`kernel/…/database/DatabaseRole.kt`).

## The tables

Every table holding an organization's data has `org_id` (`id` for the organizations table), and, in the migration
that creates it:

```sql
ALTER TABLE gates ENABLE ROW LEVEL SECURITY;
ALTER TABLE gates FORCE ROW LEVEL SECURITY;
CREATE POLICY gates_org ON gates
    USING (org_id = CAST(nullif(current_setting('ostrom.org_id', true), '') AS uuid))
    WITH CHECK (org_id = CAST(nullif(current_setting('ostrom.org_id', true), '') AS uuid));
```

Without an organization set, no row matches and nothing errors.

## The queries

A repository of a tenant table only runs inside `driver.withOrganization(orgId) { tx -> … }` (kernel,
`database/Tenancy.kt`): one transaction, `SELECT set_config('ostrom.org_id', :org, true)` first, so the setting ends
with the transaction and never leaks to the next request on the pooled connection. The block commits on return and
rolls back on any failure.

## Cross-organization operations

What must run before or across organizations (resolving a token, sign-in, creating an organization, invitations,
billing webhooks…) goes through narrow `SECURITY DEFINER` functions, each listed here with its test. They are owned
by **`ostrom_router`** (`NOLOGIN`, owner of no table, never a member of the serving role), reach the rows through a
policy `TO ostrom_router` of their tables, and set `search_path = pg_catalog, public, pg_temp` (`pg_temp` last, so
a temporary object never stands in for theirs). `EXECUTE` is revoked from `PUBLIC` and granted to `ostrom_app` only
(audit S2-60).

The kernel creates `ostrom_router` when missing (`ROUTER_ROLE_SQL`, `database/CrossOrganization.kt`); the owner of
the schema is a member, to hand the functions over (`routerFunctionsSql`, which grants `CREATE` on the schema for the
handover only, and does the `EXECUTE` revoke and grant above). A routing table (`routedTableSql`) has the tenant
policy of every table plus a policy that lets `ostrom_router` alone reach every row, with only the privileges its
functions need. Each function takes exactly the key of its operation and returns nothing for an unknown or `NULL`
key. `CREATE OR REPLACE` keeps the owner; a function re-created under a new name is handed over again.

| Function | Module | What it touches | Test |
|---|---|---|---|
| `artifacts_route_by_id(UUID)`, `artifacts_route_by_slug(TEXT)` | artifacts | reads the id and organization of one `artifacts_routes` row, by signed id or link slug (the content origin) | `ArtifactsPostgresTest.anotherOrganizationsRowsAreNeitherSeenNorWritten`, `aServingRoleFindsAnArtifactOnlyThroughTheRoutersFunctions` |
| `runners_route_by_session(UUID)`, `runners_route_by_token(TEXT)` | runners | reads the id and organization of one `runners_session_routes` row, by session id or runner token hash (the runner channel) | `CloudSessionsPostgresTest.anotherOrganizationsRowsAreNeitherSeenNorWritten`, `aServingRoleReachesRoutesOnlyThroughTheRoutersFunctions` |
| `runners_claim_due(TEXT, TIMESTAMPTZ, TIMESTAMPTZ, INTEGER)`, `runners_release(UUID, TEXT, BOOLEAN, TIMESTAMPTZ)` | runners | leases due `runners_session_routes` rows to a reconciler, at most the limit, and releases its own lease | same |
| `billing_org_of_customer(TEXT)` | billing | reads the organization of one `billing_customers` row, by Stripe customer id (the signed webhook) | `BillingPostgresTest.customersAndOutboxAreConfinedAndReachedOnlyByKey`, `aServingRoleReachesCustomersAndTheOutboxOnlyThroughTheRoutersFunctions` |
| `billing_claim_outbox(TIMESTAMPTZ, TIMESTAMPTZ, INTEGER)`, `billing_complete_outbox(UUID, TIMESTAMPTZ)`, `billing_fail_outbox(UUID, TIMESTAMPTZ)` | billing | leases due `billing_outbox` tasks to the worker, at most the limit, then marks one done or retried | same |
| `orgs_create_organization(…)` | orgs | inserts an organization and its owner | `OrgsServingRolePostgresTest` |
| `orgs_claim_holders(claim)` | orgs | reads the live organizations holding a free-plan claim | `OrgsServingRolePostgresTest` |
| `orgs_find_by_slug(slug)` | orgs | reads a live organization by short name | `OrgsServingRolePostgresTest` |
| `orgs_slug_taken(slug)` | orgs | whether a short name was ever used | `OrgsServingRolePostgresTest` |
| `orgs_memberships_of(user)` | orgs | reads a user's memberships and their organizations | `OrgsServingRolePostgresTest` |
| `orgs_live_invitation(hash, now)` | orgs | reads a live invitation by its token's hash | `OrgsServingRolePostgresTest` |

The kernel's `KernelPostgresTest.aRoutedTableIsConfinedAndOnlyTheRoutersFunctionsCrossOrganizations` proves the
mechanism itself: the function's owner is `ostrom_router`, `PUBLIC` (and so `ostrom_tenant`) cannot run it, the
serving role reads its own organization's rows only, and the function answers a known key and nothing else.

## The proof

`kernel/src/commonTest/…/TenancyPostgresTest.kt` connects as a serving role (`TestDatabase.withRole`) and proves that
an organization reads and writes only its rows, that no row is visible without an organization, that the setting does
not outlive the transaction, that the serving role cannot disable row-level security, drop a policy or create a
function or a table, and that the start check refuses a superuser, a `BYPASSRLS` role and a role that owns a table. Each module adds the same
test for each of its tenant tables.

Every PostgreSQL test runs as a deployment does (`testing/…/TestDatabase.kt`): each test database has its own owner
role, which applies the migrations, and its own serving role, owner of nothing and member of `ostrom_tenant`, with the
attributes and grants of `ci/database-roles.sql`. The test server and the tests' queries go through the serving role,
which must pass the start check, so a query that forgets its organization fails in CI as it would in production. The
configured user, a superuser, creates the database and the roles; tests use it only for what no request does
(fixtures across organizations, statistics, the roles the start check must refuse, a count across organizations).
