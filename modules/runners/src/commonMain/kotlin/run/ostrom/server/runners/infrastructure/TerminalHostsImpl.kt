// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.databaseNow
import run.ostrom.server.kernel.database.databaseNowPlus
import run.ostrom.server.kernel.database.write
import run.ostrom.server.runners.domain.TerminalHosts
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.statements.UpsertStatement
import org.jetbrains.exposed.v1.core.statements.insertValue
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * [TerminalHosts] on PostgreSQL (`runners_terminal_hosts`, [RunnersMigrations] version 11), under the organization's
 * row-level security, timed by the database's clock: a hold names [replicaUrl], and only a newer or the same execution
 * takes a session over from another replica.
 *
 * @param database The JDBC pool.
 * @param replicaUrl This replica's URL, as the others reach it.
 */
class TerminalHostsImpl(private val database: JdbcDatabase, private val replicaUrl: String) : TerminalHosts {
    override suspend fun hold(orgId: Uuid, sessionId: Uuid, epoch: Long) = database.inOrg(orgId) {
        // The organization's holds past their lease (their replica died) are dropped as new ones are taken.
        write { terminalHostPurge(orgId) }
        write { terminalHostHold(orgId, sessionId, epoch, replicaUrl) }
    }

    override suspend fun renew(orgId: Uuid, sessionId: Uuid, epoch: Long) = database.inOrg(orgId) {
        write { terminalHostRenew(orgId, sessionId, epoch, replicaUrl) }
    }

    override suspend fun release(orgId: Uuid, sessionId: Uuid, epoch: Long) = database.inOrg(orgId) {
        write { terminalHostRelease(orgId, sessionId, epoch, replicaUrl) }
    }

    override suspend fun host(orgId: Uuid, sessionId: Uuid): String? = database.inOrg(orgId) {
        terminalHostFind(orgId, sessionId).firstOrNull()?.get(RunnersTerminalHosts.replicaUrl)
    }
}

/** The organization's holds past their lease. */
internal fun StatementBuilder.terminalHostPurge(orgId: Uuid): DeleteStatement = RunnersTerminalHosts.deleteWhere {
    (RunnersTerminalHosts.orgId eq orgId) and (RunnersTerminalHosts.expiresAt lessEq databaseNow())
}

/** This replica holds the session, unless a newer execution is held elsewhere (the expired are purged first). */
internal fun StatementBuilder.terminalHostHold(
    orgId: Uuid,
    sessionId: Uuid,
    epoch: Long,
    url: String,
): UpsertStatement<Long> {
    val h = RunnersTerminalHosts
    return h.upsert(
        h.sessionId,
        onUpdate = {
            it[h.epoch] = insertValue(h.epoch)
            it[h.replicaUrl] = insertValue(h.replicaUrl)
            it[h.expiresAt] = insertValue(h.expiresAt)
        },
        where = { h.epoch lessEq epoch },
    ) { it.heldValues(orgId, sessionId, epoch, url) }
}

/**
 * This replica's hold, extended, or written again when it was lost (a failed write, a purge after a long outage); never
 * over another replica's live hold of the same or a newer execution.
 */
internal fun StatementBuilder.terminalHostRenew(
    orgId: Uuid,
    sessionId: Uuid,
    epoch: Long,
    url: String,
): UpsertStatement<Long> {
    val h = RunnersTerminalHosts
    return h.upsert(
        h.sessionId,
        onUpdate = {
            it[h.epoch] = insertValue(h.epoch)
            it[h.replicaUrl] = insertValue(h.replicaUrl)
            it[h.expiresAt] = insertValue(h.expiresAt)
        },
        where = {
            (h.epoch less epoch) or (h.expiresAt lessEq databaseNow()) or
                ((h.epoch eq epoch) and (h.replicaUrl eq url))
        },
    ) { it.heldValues(orgId, sessionId, epoch, url) }
}

/** This replica's hold, given back. */
internal fun StatementBuilder.terminalHostRelease(
    orgId: Uuid,
    sessionId: Uuid,
    epoch: Long,
    url: String,
): DeleteStatement = RunnersTerminalHosts.deleteWhere {
    (RunnersTerminalHosts.orgId eq orgId) and (RunnersTerminalHosts.sessionId eq sessionId) and
        (RunnersTerminalHosts.epoch eq epoch) and (RunnersTerminalHosts.replicaUrl eq url)
}

/** The session's live host. */
internal fun terminalHostFind(orgId: Uuid, sessionId: Uuid): Query {
    val h = RunnersTerminalHosts
    return h.select(h.replicaUrl)
        .where { (h.orgId eq orgId) and (h.sessionId eq sessionId) and (h.expiresAt greater databaseNow()) }
}

/** The values of a hold: it ends [TerminalHosts.LEASE] from now by the database's clock. */
private fun UpdateBuilder<*>.heldValues(
    orgId: Uuid,
    sessionId: Uuid,
    epoch: Long,
    url: String,
) {
    val h = RunnersTerminalHosts
    this[h.sessionId] = sessionId
    this[h.orgId] = orgId
    this[h.epoch] = epoch
    this[h.replicaUrl] = url
    this[h.expiresAt] = databaseNowPlus(TerminalHosts.LEASE)
}
