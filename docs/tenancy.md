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

### Tables without an organization

Every other table is listed here, with the role that reaches it and why no organization owns its rows;
`SchemaTenancyPostgresTest` fails on a table that has no `org_id` and is not in this list (`INSTANCE_TABLES`), and on a
listed table that gained one. The migrator's own tables, one per module (`_migrations_<module>`), are the only ones
admitted by their prefix: the owner role writes them when it migrates.

| Table | Module | Reached by | Why no organization |
|---|---|---|---|
| `billing_stripe_events` | billing | serving role, Stripe webhook | the Stripe events already handled, by Stripe's id, before the organization is known |
| `github_apps` | integrations-github | serving role | the instance's one GitHub App, its secrets sealed |
| `github_installation_origins` | integrations-github | serving role, GitHub webhook | who installed an installation, read before its organization is known; row-level security forced, one row opened by `ostrom.github_installation` (R7) |
| `github_manifest_states` | integrations-github | serving role | the state of an instance admin's GitHub App creation, by hash |
| `github_webhook_deliveries` | integrations-github | serving role, GitHub webhook | the webhook deliveries already received (an id and a time), to drop a replay |
| `identity_device_authorizations` | identity | serving role, device sign-in | a person's pending CLI sign-in |
| `identity_identities` | identity | serving role, sign-in | a person's external accounts |
| `identity_magic_links` | identity | serving role, sign-in | e-mail sign-in links, by hash |
| `identity_mobile_codes` | identity | serving role, the app's sign-in | one-time codes, by hash |
| `identity_notification_preferences` | identity | serving role | a person's preferences, across their organizations |
| `identity_oauth_attempts` | identity | serving role, sign-in | an OAuth round trip's state, by hash |
| `identity_sign_in_links` | identity | serving role | a signed-in person's one-time link to a browser, by hash |
| `identity_user_emails` | identity | serving role, sign-in | a person's e-mail addresses |
| `identity_users` | identity | serving role | the people, members of any number of organizations |
| `identity_web_sessions` | identity | serving role | a person's browser sessions, by hash, read before an organization is chosen |
| `instance_info` | instance | serving role | the instance's one identity row |
| `instance_signing_keys` | instance | serving role | the public keys of the instance's receipt signatures |

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

| Function | Module | What it touches | Test |
|---|---|---|---|
| `orgs_create_organization(…)` | orgs | inserts an organization and its owner | `OrgsServingRolePostgresTest` |
| `orgs_claim_holders(claim)` | orgs | reads the live organizations holding a free-plan claim | `OrgsServingRolePostgresTest` |
| `orgs_find_by_slug(slug)` | orgs | reads a live organization by short name | `OrgsServingRolePostgresTest` |
| `orgs_slug_taken(slug)` | orgs | whether a short name was ever used | `OrgsServingRolePostgresTest` |
| `orgs_memberships_of(user)` | orgs | reads a user's memberships and their organizations | `OrgsServingRolePostgresTest` |
| `orgs_live_invitation(hash, now)` | orgs | reads a live invitation by its token's hash | `OrgsServingRolePostgresTest` |
| `identity_token_by_hash(hash)` | identity | reads an API token by its hash, to authenticate it | `IdentityTenancyPostgresTest` |
| `identity_token_touch(id, at)` | identity | notes an API token's last use | `IdentityTenancyPostgresTest` |
| `identity_revoke_agent_session_tokens(session, at)` | identity | revokes the agent tokens of one agent session | `IdentityTenancyPostgresTest` |
| `automations_route_org(id)` | automations | an automation's organization, for its webhook | `AutomationsRoutesTenancyPostgresTest` |
| `automations_due_routes(now, limit)` | automations | claims the due crons of every organization (`FOR UPDATE SKIP LOCKED`) | `AutomationsPostgresTest` |
| `automations_organizations()` | automations | the organizations that have automations, for the retention jobs | `AutomationsPostgresTest` |
| `sessions_stale(cutoff, limit)` | sessions | the open sessions of every organization last seen before the cutoff, for the sweep | `SessionsPostgresTest` |
| `usage_purge(before, limit)` | usage | deletes one batch of events older than the retention | `UsagePostgresTest` |
| `mcp_relay_purge(before)` | mcp-relay | deletes the calls older than the retention | `McpRelayPostgresTest` |
| `gates_pending_count()` | gates | counts the pending gates (sees `status` alone), for the `ostrom.gates.pending` gauge | `GatesPostgresTest` |
| `sessions_active_count(since)` | sessions | counts the open sessions of every organization seen since, for `ostrom.sessions.active` | `SessionsPostgresTest` |
| `artifacts_route_by_id(id)` | artifacts | an artifact's organization by its id, for the content origin | `ArtifactsRoutesTenancyPostgresTest` |
| `artifacts_route_by_slug(slug)` | artifacts | an artifact's organization by its public link | `ArtifactsRoutesTenancyPostgresTest` |
| `runners_session_org(id)` | runners | a session's organization | `RunnersRoutesTenancyPostgresTest` |
| `runners_session_by_token(hash)` | runners | the session a runner token belongs to, for the runner channel | `RunnersRoutesTenancyPostgresTest` |
| `runners_claim_due(owner, until, now, limit)` | runners | leases the due sessions of every organization to one reconciler (`FOR UPDATE SKIP LOCKED`) | `CloudSessionsPostgresTest` |
| `runners_release(id, owner, has_next, next_at)` | runners | ends a reconciler's lease, keeping a look asked for meanwhile | `CloudSessionsPostgresTest` |
| `billing_customer_org(customer)` | billing | an organization by its Stripe customer, for the webhook | `BillingPostgresTest` |
| `billing_claim_due_tasks(now, lease, limit)` | billing | leases the due outbox tasks of every organization (`FOR UPDATE SKIP LOCKED`) | `BillingPostgresTest` |
| `billing_complete_task(id, at)`, `billing_fail_task(id, retry)` | billing | ends or retries an outbox task | `BillingPostgresTest` |

## Known exceptions

`app/…/SchemaTenancyPostgresTest.kt` reads the catalog once every module has migrated and checks the rules above for
every table, policy, grant and `SECURITY DEFINER` function. The exceptions it still lists are open findings of the RLS
audit, each to be closed by moving the cross-organization path into a function of the table above:

- policies a setting opens to one row of any organization: the read and delete policies of
  `github_installations` (`ostrom.github_installation`) and `github_install_states` (`ostrom.github_state`).

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
