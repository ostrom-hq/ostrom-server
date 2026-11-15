// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.appendTo
import org.jetbrains.exposed.v1.core.booleanLiteral
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select

/** The setting a webhook's transaction names its installation with, once the webhook's signature is checked. */
internal const val INSTALLATION_SETTING = "ostrom.github_installation"

/** The setting the install callback's transaction names the hash of its state with. */
internal const val STATE_SETTING = "ostrom.github_state"

/**
 * Runs [block] in one transaction that first sets [setting] to [value] for this transaction alone
 * (`set_config(…, true)`): the one key a row level security policy of the module lets it see, beside an organization.
 */
internal suspend fun <T> JdbcDatabase.keyed(setting: String, value: String, block: JdbcTransaction.() -> T): T = query {
    val set = CustomFunction(
        "set_config",
        TextColumnType(),
        QueryParameter(setting, TextColumnType()),
        QueryParameter(value, TextColumnType()),
        booleanLiteral(true),
    )
    Table.Dual.select(set).toList()
    block()
}

/** What `github_links_of_other_clients(client)` returns, read as a table: members whose link another client issued. */
internal object GitHubLinksOfOtherClients : Table("github_links_of_other_clients") {
    val orgId = uuid("org_id")
    val memberId = uuid("member_id")

    /** The function called for [clientId], a function of `ostrom_router` (docs/tenancy.md). */
    fun of(clientId: String): ColumnSet = FunctionCall(tableName, listOf(QueryParameter(clientId, TextColumnType())))
}

/** A set-returning function called with its [arguments], read as a table of its own name. */
private class FunctionCall(name: String, private val arguments: List<Expression<*>>) : Table(name) {
    override fun describe(s: Transaction, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append(tableName)
        arguments.appendTo(this, prefix = "(", postfix = ")") { +it }
    }
}

/** The count of the installations across organizations, a function of `ostrom_router` (docs/tenancy.md). */
internal fun JdbcTransaction.installationsCount(): Long {
    val call = CustomFunction("github_installations_count", LongColumnType())
    return Table.Dual.select(call).single()[call]
}
