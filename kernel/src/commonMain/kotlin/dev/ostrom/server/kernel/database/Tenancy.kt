// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.database

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlin.uuid.Uuid

/**
 * The PostgreSQL role every organization-scoped query runs as. It is not a superuser and owns no table, so the
 * row-level security policies of the tenant tables always apply to it, even when the server connects as the
 * tables' owner or as a superuser (which bypass them). The identity module's first migration creates it and grants
 * it to the server's user.
 */
const val TENANT_ROLE = "ostrom_tenant"

/** The transaction-local setting the row-level security policies read: the organization of the query. */
const val TENANT_SETTING = "ostrom.org_id"

/**
 * Runs [block] in a transaction confined to one organization: the transaction switches to [TENANT_ROLE] and sets
 * [TENANT_SETTING], in one statement before [block] ([IN_ORG]), so a tenant table only shows and accepts the rows of
 * [orgId], whatever the query says. A missing `WHERE org_id = …` then reads nothing of another organization instead
 * of leaking it.
 *
 * A tenant table declares, in its module's migration:
 * ```sql
 * ALTER TABLE orgs_members ENABLE ROW LEVEL SECURITY;
 * CREATE POLICY orgs_members_tenant ON orgs_members
 *     USING (org_id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID))
 *     WITH CHECK (org_id = CAST(NULLIF(current_setting('ostrom.org_id', true), '') AS UUID));
 * GRANT SELECT, INSERT, UPDATE, DELETE ON orgs_members TO ostrom_tenant;
 * ```
 *
 * The serving role owns no table (S2-01), so the policies bind it whether the table is `FORCE`d or not: work that is
 * not one organization's (listing the organizations of a user) goes through a `SECURITY DEFINER` function
 * (docs/tenancy.md), never through the serving role without an organization, which sees no row.
 *
 * @param orgId The organization the queries are confined to.
 * @param block The queries.
 *
 * @return What [block] returns.
 */
suspend fun <T> Driver.inOrg(orgId: Uuid, block: suspend Transaction.() -> T): T = transaction {
    fetchAll(Statement.create(IN_ORG).bind("org", orgId.toString())).getOrThrow()
    block()
}

/**
 * The one statement that confines a transaction of [inOrg], one round trip instead of two (perf pass of the launch):
 * `set_config('role', …, true)` is `SET LOCAL ROLE`, with the same membership check and the same end at the
 * transaction's end; `set_config(…, true)` of the organization is transaction-local too.
 */
private const val IN_ORG =
    "SELECT set_config('role', '$TENANT_ROLE', true), set_config('$TENANT_SETTING', :org, true)"

/**
 * Runs [block] in a transaction scoped to one organization, the only way a repository of tenant tables reaches the
 * database (CONVENTIONS 16.1). `ostrom.org_id` is set for this transaction alone (`set_config(…, true)`): the
 * row-level security policies of tenant tables then show and accept that organization's rows only, and the setting
 * never outlives the transaction on the pooled connection. The block commits on return and rolls back on any
 * failure, cancellation included.
 *
 * @param orgId The organization.
 * @param block The work, given the transaction; it never sees the pool.
 *
 * @return What [block] returns.
 */
suspend fun <T> Driver.withOrganization(orgId: Uuid, block: suspend (Transaction) -> T): T = transaction {
    fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, true)").bind("org", orgId.toString()))
        .getOrThrow()
    block(this)
}
