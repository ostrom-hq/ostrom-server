// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.infrastructure

import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import kotlin.uuid.Uuid

/**
 * One transaction of the compiler's storage, with the organization set for the row level security.
 *
 * @param executor The transaction.
 */
internal class CompilerTx(private val executor: QueryExecutor) {
    suspend fun fetch(statement: Statement): List<ResultSet.Row> = executor.fetchAll(statement).getOrThrow().toList()

    suspend fun execute(statement: Statement): Long = executor.execute(statement).getOrThrow()
}

// Runs [block] in one transaction that first sets `ostrom.org_id`, read by the policies of the compiler tables.
internal suspend fun <T> Driver.inOrg(orgId: Uuid, block: suspend CompilerTx.() -> T): T = transaction {
    CompilerTx(this).run {
        fetch(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgId.toString()))
        block()
    }
}
