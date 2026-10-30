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
 * [TENANT_SETTING], so a tenant table only shows and accepts the rows of [orgId], whatever the query says. A missing
 * `WHERE org_id = …` then reads nothing of another organization instead of leaking it.
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
 * The table is not `FORCE`d: the server's own role (the owner) keeps a path for work that is not one
 * organization's (listing the organizations of a user), and every organization-scoped repository goes through here.
 *
 * @param orgId The organization the queries are confined to.
 * @param block The queries.
 *
 * @return What [block] returns.
 */
suspend fun <T> Driver.inOrg(orgId: Uuid, block: suspend Transaction.() -> T): T = transaction {
    execute("SET LOCAL ROLE $TENANT_ROLE").getOrThrow()
    execute(Statement.create("SELECT set_config('$TENANT_SETTING', :org, true)").bind("org", orgId.toString()))
        .getOrThrow()
    block()
}
