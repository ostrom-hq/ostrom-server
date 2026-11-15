// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.databaseNow
import run.ostrom.server.kernel.database.databaseNowPlus
import run.ostrom.server.kernel.database.write
import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.RunnerTokens
import run.ostrom.server.runners.domain.TerminalTicket
import run.ostrom.server.runners.domain.TerminalTickets
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.ReturningStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Terminal tickets in PostgreSQL (`runners_terminal_tickets`, [RunnersMigrations] version 9), so that a ticket issued
 * by one replica opens the terminal on any other (TC-REP-2). A ticket is random, lives [lifetime] by the database's
 * clock, opens one session's terminal once, and only its hash is kept, in its organization's rows under row-level
 * security. The organization is written in the ticket (`ostrom_term_<org>_<secret>`): its redemption, before any
 * credential, reads inside that organization only, and a ticket naming another organization finds nothing there.
 *
 * @param database The JDBC pool.
 * @param tokens Draws and hashes the random values.
 * @param lifetime How long a ticket stays valid.
 */
class TerminalTicketsImpl(
    private val database: JdbcDatabase,
    private val tokens: RunnerTokens,
    private val lifetime: Duration = DEFAULT_LIFETIME,
) : TerminalTickets {
    override suspend fun issue(caller: Caller, sessionId: Uuid): TerminalTicket {
        val value = PREFIX + caller.orgId.toHexString() + "_" + tokens.mint().substringAfter('_').substringAfter('_')
        val hash = tokens.hash(value)
        val expiresAt = database.inOrg(caller.orgId) {
            // The organization's tickets past their end, never read again, are dropped as new ones are issued.
            write { ticketPurge(caller.orgId) }
            returned { ticketInsert(caller, sessionId, hash, lifetime) }.single()[RunnersTerminalTickets.expiresAt]
        }
        return TerminalTicket(value, expiresAt)
    }

    override suspend fun redeem(value: String, sessionId: Uuid): Caller? {
        val orgId = orgOf(value) ?: return null
        val hash = tokens.hash(value)
        // Spent whatever happens next: a ticket tried on another session opens nothing afterwards.
        val row = database.inOrg(orgId) { returned { ticketRedeem(orgId, hash) }.firstOrNull() }
        val t = RunnersTerminalTickets
        val live = row != null && row[LIVE] && row[t.sessionId] == sessionId
        return if (live) Caller(orgId, row[t.memberId], row[t.admin]) else null
    }

    private fun orgOf(value: String): Uuid? {
        if (!value.startsWith(PREFIX)) return null
        val hex = value.removePrefix(PREFIX).substringBefore('_', "")
        return if (hex.length == ORG_HEX_LENGTH) Uuid.parseHexOrNull(hex) else null
    }

    /** Constants and readers of the enclosing type. */
    companion object {
        const val PREFIX = "ostrom_term_"
        private const val ORG_HEX_LENGTH = 32
        private val DEFAULT_LIFETIME = 60.seconds
    }
}

/** Whether a ticket redeemed was still valid, as the redemption returns it. */
private val LIVE = RunnersTerminalTickets.expiresAt greater databaseNow()

/** A new ticket, ending [lifetime] from now by the database's clock; returns when it ends. */
internal fun StatementBuilder.ticketInsert(
    caller: Caller,
    sessionId: Uuid,
    hash: String,
    lifetime: Duration,
): ReturningStatement {
    val t = RunnersTerminalTickets
    return t.insertReturning(listOf(t.expiresAt)) {
        it[tokenHash] = hash
        it[orgId] = caller.orgId
        it[this.sessionId] = sessionId
        it[memberId] = caller.memberId
        it[admin] = caller.admin
        it[expiresAt] = databaseNowPlus(lifetime)
    }
}

/** The organization's tickets past their end. */
internal fun StatementBuilder.ticketPurge(orgId: Uuid): DeleteStatement = RunnersTerminalTickets.deleteWhere {
    (RunnersTerminalTickets.orgId eq orgId) and (RunnersTerminalTickets.expiresAt lessEq databaseNow())
}

/** A ticket spent: deleted, with what it opens and whether it was still valid. */
internal fun StatementBuilder.ticketRedeem(orgId: Uuid, hash: String): ReturningStatement {
    val t = RunnersTerminalTickets
    return t.deleteReturning(listOf(t.sessionId, t.memberId, t.admin, LIVE)) {
        (t.orgId eq orgId) and (t.tokenHash eq hash)
    }
}
