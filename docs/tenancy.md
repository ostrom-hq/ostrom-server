# Tenancy: how one organization never sees another's rows

The rule is CONVENTIONS section 16.1 (ostrom-core); this page says how `ostrom-server` applies it.

## The roles

- The **owner** owns the schema and every table. It is used only by `ostrom-server migrate`
  (`OSTROM_DATABASE_MIGRATION_URL`). The migration lock is a PostgreSQL advisory lock, waited for without a timeout:
  a second `migrate` waits for the first to finish.
- The **serving role** (`ostrom_app` in the Helm chart) owns nothing and is `NOSUPERUSER NOBYPASSRLS`: row-level
  security applies to it, `FORCE ROW LEVEL SECURITY` included. It can read and write rows, never alter a table, a
  policy or a function, nor create one.
- At start the server refuses to serve (`UnsafeDatabaseRoleException`, exit 1) when its role is a superuser, has
  `BYPASSRLS`, or owns a table of the schema or is a member of its owner (`pg_has_role(current_user, relowner,
  'USAGE')`): such a role could reach, or unlock, every organization's rows (`kernel/…/database/DatabaseRole.kt`).

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
billing webhooks…) goes through narrow `SECURITY DEFINER` functions, each listed here with its test. None exists yet:
the module that adds the first one lists it below, with its owner role and grants.

| Function | Module | What it touches | Test |
|---|---|---|---|

## The proof

`kernel/src/commonTest/…/TenancyPostgresTest.kt` connects as a serving role (`TestDatabase.withRole`) and proves that
an organization reads and writes only its rows, that no row is visible without an organization, that the setting does
not outlive the transaction, that the serving role cannot disable row-level security, drop a policy or create a
function or a table, and that the start check refuses a superuser, a `BYPASSRLS` role and a role that owns a table. Each module adds the same
test for each of its tenant tables.
