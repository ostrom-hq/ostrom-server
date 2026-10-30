// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.kernel.database

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlin.uuid.Uuid

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
