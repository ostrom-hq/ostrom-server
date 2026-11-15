// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.appendTo

// The function of `ostrom_router` the module reads across organizations (McpRelayMigrations version 7), its argument
// bound by Exposed; `mcp_relay_purge` (version 4) is called in the repository, as it returns a count.

/** What `mcp_relay_hosted_assignments(limit)` returns, read as a table: the assignments of every stdio server. */
internal object McpHostedAssignments : Table("mcp_relay_hosted_assignments") {
    val orgId = uuid("org_id")
    val serverId = uuid("server_id")
    val version = integer("version")
    val sharing = text("sharing")
    val memberId = uuid("member_id").nullable()
    val assignedAt = instant("assigned_at")

    /**
     * The function called for at most [limit] assignments.
     *
     * @param limit The most rows.
     */
    fun of(limit: Int): ColumnSet = FunctionCall(tableName, QueryParameter(limit, IntegerColumnType()))
}

/** A set-returning function called with its [argument], read as a table of its own name. */
private class FunctionCall(name: String, private val argument: Expression<*>) : Table(name) {
    override fun describe(s: Transaction, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append(tableName)
        listOf(argument).appendTo(this, prefix = "(", postfix = ")") { +it }
    }
}
