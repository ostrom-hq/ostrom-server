// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import kotlin.uuid.Uuid

/**
 * Runs [block] in one transaction scoped to one organization as the serving role, as the sqlx4k [withOrganization]:
 * [TENANT_SETTING] is set for this transaction alone (`set_config(…, true)`), the role is not switched. For the
 * writes a tenant table's policy accepts only from the serving role inside the organization (the identity module's
 * tokens: `ostrom_tenant` only reads them); every other tenant query runs in [JdbcDatabase.inOrg].
 *
 * @param orgId The organization.
 * @param block The queries, not `suspend` ([JdbcDatabase.query]).
 *
 * @return What [block] returns, after the commit.
 */
suspend fun <T> JdbcDatabase.withOrganization(orgId: Uuid, block: JdbcTransaction.() -> T): T = query {
    exec("SELECT set_config('$TENANT_SETTING', ?, true)", listOf(TextColumnType() to orgId.toString())) {}
    block()
}

/**
 * Takes the PostgreSQL advisory lock of [key] until this transaction ends (`pg_advisory_xact_lock(hashtext(key))`):
 * the same lock a sqlx4k transaction takes with the same SQL, so writers on either pool take turns.
 *
 * @param key What is locked (an audit chain).
 */
fun JdbcTransaction.lockUntilCommit(key: String) {
    exec("SELECT pg_advisory_xact_lock(hashtext(?))", listOf(TextColumnType() to key)) {}
}
