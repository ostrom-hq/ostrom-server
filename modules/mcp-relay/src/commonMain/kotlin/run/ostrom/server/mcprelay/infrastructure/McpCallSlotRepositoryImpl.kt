// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.Read
import run.ostrom.server.kernel.database.asBigint
import run.ostrom.server.kernel.database.countWhere
import run.ostrom.server.kernel.database.databaseNow
import run.ostrom.server.kernel.database.databaseNowPlus
import run.ostrom.server.kernel.database.write
import run.ostrom.server.mcprelay.domain.McpCallSlot
import run.ostrom.server.mcprelay.domain.McpCallSlotRepository
import run.ostrom.server.mcprelay.domain.McpLoad
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.coalesce
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.div
import org.jetbrains.exposed.v1.core.doubleLiteral
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.longLiteral
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.core.statements.UpsertStatement
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * [McpCallSlotRepository] on PostgreSQL through Exposed ([McpRelayMigrations] version 6), every statement in a
 * transaction scoped to one organization (`inOrg`) with its own `org_id` filter. An admission locks the server's row
 * (`FOR UPDATE`), so two replicas admitting on one server take turns and never pass a limit together; it drops the
 * server's expired slots and past minutes as it goes, so neither table grows with dead rows. The lock, the purges,
 * the usage read and the two inserts are one transaction.
 *
 * @param database The serving role's pool.
 */
class McpCallSlotRepositoryImpl(private val database: JdbcDatabase) : McpCallSlotRepository {
    override suspend fun acquire(
        orgId: Uuid,
        memberId: Uuid,
        serverId: Uuid,
        admit: (McpLoad) -> Boolean,
    ): McpCallSlot? = database.inOrg(orgId) {
        if (lockedServer(orgId, serverId).toList().isEmpty()) return@inOrg null
        write { purgeSlots(orgId, serverId) }
        write { purgeMinutes(orgId, serverId) }
        val (memberRunning, serverRunning) = runningSlots(orgId, serverId, memberId).single()
        val usage = McpLoad(
            memberRunning = memberRunning.toInt(),
            serverRunning = serverRunning.toInt(),
            memberThisMinute = minuteCalls(orgId, serverId, memberId).single().toInt(),
            serverThisMinute = minuteCalls(orgId, serverId, null).single().toInt(),
        )
        if (!admit(usage)) return@inOrg null
        val slot = McpCallSlot(Uuid.random())
        write { takeSlot(orgId, serverId, memberId, slot.id) }
        write { countMinute(orgId, serverId, memberId) }
        slot
    }

    override suspend fun renew(orgId: Uuid, slot: McpCallSlot) {
        database.inOrg(orgId) { write { renewSlot(orgId, slot.id) } }
    }

    override suspend fun release(orgId: Uuid, slot: McpCallSlot) {
        database.inOrg(orgId) { write { releaseSlot(orgId, slot.id) } }
    }
}

private val SLOT_LEASE = McpCallSlotRepository.SLOT_LEASE

/**
 * The current minute since the epoch, by the database's clock:
 * `CAST(FLOOR(date_part('epoch', now()) / 60) AS BIGINT)`, the same minute on every replica.
 */
private fun databaseMinute(): ExpressionWithColumnType<Long> {
    val epoch = CustomFunction("date_part", DoubleColumnType(), stringLiteral("epoch"), databaseNow())
    return CustomFunction("FLOOR", DoubleColumnType(), epoch / doubleLiteral(SECONDS_A_MINUTE)).asBigint()
}

private const val SECONDS_A_MINUTE = 60.0

/** The server's row, locked: admissions on one server take turns. */
internal fun lockedServer(orgId: Uuid, serverId: Uuid): Query {
    val s = McpRelayServers
    return s.select(s.id).where { (s.orgId eq orgId) and (s.id eq serverId) }.forUpdate()
}

/** The server's expired slots, dropped. */
internal fun StatementBuilder.purgeSlots(orgId: Uuid, serverId: Uuid): DeleteStatement {
    val s = McpRelaySlots
    return s.deleteWhere { (s.orgId eq orgId) and (s.serverId eq serverId) and (s.expiresAt lessEq databaseNow()) }
}

/** The server's past minutes, dropped. */
internal fun StatementBuilder.purgeMinutes(orgId: Uuid, serverId: Uuid): DeleteStatement {
    val m = McpRelayMinutes
    return m.deleteWhere { (m.orgId eq orgId) and (m.serverId eq serverId) and (m.minute less databaseMinute()) }
}

/** What the member and the server run now: the member's running operations and the server's. */
internal fun runningSlots(orgId: Uuid, serverId: Uuid, memberId: Uuid): Read<Pair<Long, Long>> {
    val s = McpRelaySlots
    val member = countWhere(s.memberId eq memberId)
    val server = s.id.count()
    val query = s.select(member, server).where { (s.orgId eq orgId) and (s.serverId eq serverId) }
    return Read(query) { it[member] to it[server] }
}

/** What the member (or, when null, the whole server) started this minute. */
internal fun minuteCalls(orgId: Uuid, serverId: Uuid, memberId: Uuid?): Read<Long> {
    val m = McpRelayMinutes
    val total = coalesce(m.calls.sum().asBigint(), longLiteral(0))
    val query = m.select(total).where {
        val ofMember = if (memberId == null) Op.TRUE else m.memberId eq memberId
        (m.orgId eq orgId) and (m.serverId eq serverId) and (m.minute eq databaseMinute()) and ofMember
    }
    return Read(query) { it[total] }
}

/** A new slot, expiring after its lease. */
internal fun StatementBuilder.takeSlot(orgId: Uuid, serverId: Uuid, memberId: Uuid, id: Uuid): InsertStatement<Number> {
    val s = McpRelaySlots
    return s.insert {
        it[this.id] = id
        it[this.orgId] = orgId
        it[this.serverId] = serverId
        it[this.memberId] = memberId
        it[expiresAt] = databaseNowPlus(SLOT_LEASE)
    }
}

/**
 * One more operation of the member on the server this minute: `ON CONFLICT (server_id, minute, member_id) DO UPDATE
 * SET calls = mcp_relay_minutes.calls + 1`.
 */
internal fun StatementBuilder.countMinute(orgId: Uuid, serverId: Uuid, memberId: Uuid): UpsertStatement<Long> {
    val m = McpRelayMinutes
    return m.upsert(m.serverId, m.minute, m.memberId, onUpdate = { it[m.calls] = m.calls + 1 }) {
        it[this.orgId] = orgId
        it[this.serverId] = serverId
        it[minute] = databaseMinute()
        it[this.memberId] = memberId
        it[calls] = 1
    }
}

/** A slot still held, extended by its lease from now. */
internal fun StatementBuilder.renewSlot(orgId: Uuid, id: Uuid): UpdateStatement {
    val s = McpRelaySlots
    return s.update({ (s.orgId eq orgId) and (s.id eq id) }) { it[expiresAt] = databaseNowPlus(SLOT_LEASE) }
}

/** One slot given back. */
internal fun StatementBuilder.releaseSlot(orgId: Uuid, id: Uuid): DeleteStatement {
    val s = McpRelaySlots
    return s.deleteWhere { (s.orgId eq orgId) and (s.id eq id) }
}
