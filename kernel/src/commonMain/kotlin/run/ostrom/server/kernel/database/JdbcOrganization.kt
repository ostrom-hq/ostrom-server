// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import kotlin.uuid.Uuid

/**
 * Runs [block] in one transaction scoped to one organization as the serving role: [TENANT_SETTING] is set for this
 * transaction alone (`set_config(…, true)`), the role is not switched. For the writes a tenant table's policy accepts
 * only from the serving role inside the organization (the identity module's tokens: `ostrom_tenant` only reads them);
 * every other tenant query runs in [JdbcDatabase.inOrg].
 *
 * @param orgId The organization.
 * @param block The queries, not `suspend` ([JdbcDatabase.query]).
 *
 * @return What [block] returns, after the commit.
 */
suspend fun <T> JdbcDatabase.withOrganization(orgId: Uuid, block: JdbcTransaction.() -> T): T = query {
    confineToOrganization(orgId)
    block()
}

/**
 * Switches this transaction to [TENANT_ROLE] (`SET LOCAL ROLE`), until it ends: the row-level security policies of the
 * tenant tables then bind it. [JdbcDatabase.inOrg] does it with [confineToOrganization]; a transaction that has to
 * start as the serving role (a function only it may run) and then confines itself, one organization after another
 * (the automations scheduler's claim), calls them in that order.
 */
fun JdbcTransaction.switchToTenantRole() {
    exec("SET LOCAL ROLE $TENANT_ROLE")
}

/**
 * Sets [TENANT_SETTING] to [orgId] for this transaction alone (`set_config(…, true)`): the policies of the tenant
 * tables show and accept that organization's rows. Called again, it moves to another organization.
 *
 * @param orgId The organization.
 */
fun JdbcTransaction.confineToOrganization(orgId: Uuid) {
    exec("SELECT set_config('$TENANT_SETTING', ?, true)", listOf(TextColumnType() to orgId.toString())) {}
}

/**
 * The SQL of [lockUntilCommit]: `pg_advisory_xact_lock(hashtext(key))`, the key bound as the one positional parameter.
 */
const val LOCK_UNTIL_COMMIT_SQL = "SELECT pg_advisory_xact_lock(hashtext(?))"

/**
 * Takes the PostgreSQL advisory lock of [key] until this transaction ends ([LOCK_UNTIL_COMMIT_SQL]): the writers of
 * one key (an audit chain) take turns.
 *
 * @param key What is locked (an audit chain).
 */
fun JdbcTransaction.lockUntilCommit(key: String) {
    exec(LOCK_UNTIL_COMMIT_SQL, listOf(TextColumnType() to key)) {}
}
