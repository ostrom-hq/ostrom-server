// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.Caller
import run.ostrom.server.runners.domain.RunnerTokens
import run.ostrom.server.runners.domain.TerminalTicket
import run.ostrom.server.runners.domain.TerminalTickets
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
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
 * @param driver The database.
 * @param tokens Draws and hashes the random values.
 * @param lifetime How long a ticket stays valid.
 */
class TerminalTicketsImpl(
    private val driver: Driver,
    private val tokens: RunnerTokens,
    private val lifetime: Duration = DEFAULT_LIFETIME,
) : TerminalTickets {
    override suspend fun issue(caller: Caller, sessionId: Uuid): TerminalTicket {
        val value = PREFIX + caller.orgId.toHexString() + "_" + tokens.mint().substringAfter('_').substringAfter('_')
        val insert = Statement.create(TERMINAL_TICKET_INSERT_SQL)
            .bind("hash", tokens.hash(value)).bind("org_id", caller.orgId).bind("session_id", sessionId)
            .bind("member_id", caller.memberId).bind("admin", caller.admin)
            .bind("seconds", lifetime.inWholeSeconds)
        val expiresAt = driver.inTenant(caller.orgId) {
            // The organization's tickets past their end, never read again, are dropped as new ones are issued.
            execute(Statement.create(TERMINAL_TICKET_PURGE_SQL).bind("org_id", caller.orgId)).getOrThrow()
            fetchAll(insert).getOrThrow().single().get("expires_at").asInstant()
        }
        return TerminalTicket(value, expiresAt)
    }

    override suspend fun redeem(value: String, sessionId: Uuid): Caller? {
        val orgId = orgOf(value) ?: return null
        val redeem = Statement.create(TERMINAL_TICKET_REDEEM_SQL).bind("org_id", orgId).bind("hash", tokens.hash(value))
        // Spent whatever happens next: a ticket tried on another session opens nothing afterwards.
        val row = driver.inTenant(orgId) { fetchAll(redeem).getOrThrow().firstOrNull() } ?: return null
        val live = row.get("live").asBoolean() && row.get("session_id").asUuid() == sessionId
        return if (live) Caller(orgId, row.get("member_id").asString(), row.get("admin").asBoolean()) else null
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

/** A new ticket, ending [TerminalTicketsImpl]'s lifetime from now by the database's clock. */
internal const val TERMINAL_TICKET_INSERT_SQL =
    "INSERT INTO runners_terminal_tickets (token_hash, org_id, session_id, member_id, admin, expires_at) " +
        "VALUES (:hash, :org_id, :session_id, :member_id, :admin, now() + make_interval(secs => :seconds)) " +
        "RETURNING expires_at"

/** The organization's tickets past their end. */
internal const val TERMINAL_TICKET_PURGE_SQL =
    "DELETE FROM runners_terminal_tickets WHERE org_id = :org_id AND expires_at <= now()"

/** A ticket spent: deleted, with what it opens and whether it was still valid. */
internal const val TERMINAL_TICKET_REDEEM_SQL =
    "DELETE FROM runners_terminal_tickets WHERE org_id = :org_id AND token_hash = :hash " +
        "RETURNING session_id, member_id, admin, expires_at > now() AS live"
