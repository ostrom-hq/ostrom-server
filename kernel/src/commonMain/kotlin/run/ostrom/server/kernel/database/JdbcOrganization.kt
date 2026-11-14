// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.database

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
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
 * The SQL of [lockUntilCommit], one for both pools: `pg_advisory_xact_lock(hashtext(key))`, the key bound as the one
 * positional parameter. A writer on either pool takes the same lock of the same key, so they take turns.
 */
const val LOCK_UNTIL_COMMIT_SQL = "SELECT pg_advisory_xact_lock(hashtext(?))"

/**
 * Takes the PostgreSQL advisory lock of [key] until this transaction ends ([LOCK_UNTIL_COMMIT_SQL]): the same lock as
 * a sqlx4k transaction's [Transaction.lockUntilCommit], so writers on either pool take turns.
 *
 * @param key What is locked (an audit chain).
 */
fun JdbcTransaction.lockUntilCommit(key: String) {
    exec(LOCK_UNTIL_COMMIT_SQL, listOf(TextColumnType() to key)) {}
}

/**
 * Takes the PostgreSQL advisory lock of [key] until this sqlx4k transaction ends ([LOCK_UNTIL_COMMIT_SQL]): the same
 * lock as an Exposed transaction's [JdbcTransaction.lockUntilCommit]. For the modules still on sqlx4k.
 *
 * @param key What is locked (an audit chain).
 */
suspend fun Transaction.lockUntilCommit(key: String) {
    execute(Statement.create(LOCK_UNTIL_COMMIT_SQL).bind(0, key)).getOrThrow()
}
