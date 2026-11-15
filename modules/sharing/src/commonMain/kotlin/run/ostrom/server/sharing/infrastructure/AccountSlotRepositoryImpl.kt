// SPDX-License-Identifier: Apache-2.0
// The repository and its reads, one function each, so that the query-plan test explains the very statements it sends.
@file:Suppress("TooManyFunctions")

package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.databaseNow
import run.ostrom.server.kernel.database.databaseNowPlus
import run.ostrom.server.kernel.database.write
import run.ostrom.server.sharing.domain.AccountSlot
import run.ostrom.server.sharing.domain.AccountSlotRepository
import run.ostrom.server.sharing.domain.ActiveSession
import run.ostrom.server.sharing.domain.RefreshOwnership
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.SlotView
import java.security.MessageDigest
import org.jetbrains.exposed.v1.core.Count
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.anyFrom
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.core.statements.UpsertStatement
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * [AccountSlotRepository] on PostgreSQL ([SharingMigrations] version 8) through Exposed. A take locks the candidates'
 * rows (`FOR UPDATE`, in id order so that two takes never deadlock), counts their unexpired slots, and inserts the slot
 * in the same transaction: two replicas never fill an account past its limit together. Expiry is the database's clock.
 * A session id is stored as its SHA-256 digest only. The org's expired slots and affinities are deleted as a take
 * goes, so neither table keeps an org's dead rows past its next lease.
 *
 * @param database The serving role's pool.
 */
class AccountSlotRepositoryImpl(private val database: JdbcDatabase) : AccountSlotRepository {
    override suspend fun take(
        orgId: Uuid,
        memberId: Uuid,
        session: String?,
        candidates: List<SharedAccount>,
        pick: (SlotView) -> SharedAccount?,
    ): AccountSlot? = database.inOrg(orgId) {
        val ids = candidates.map { it.id }
        val serving = slotLock(orgId, ids).map { it[SharingAccounts.id] }.toSet()
        val active = counts(slotsActive(orgId, ids))
        val hash = if (session == null) null else sessionHash(session)
        val remembered = if (hash == null) null else slotRemembered(orgId, memberId, hash).firstOrNull()
        val account = pick(SlotView(active, remembered?.get(SharingAffinities.accountId), serving))
        if (account == null) return@inOrg null
        val slot = AccountSlot(Uuid.random(), account.id)
        write { slotPurge(orgId) }
        write { slotInsert(orgId, slot) }
        if (hash != null) {
            write { affinityPurge(orgId) }
            if (mayRemember(orgId, memberId, hash)) write { affinityRemember(orgId, memberId, hash, account.id) }
        }
        slot
    }

    override suspend fun release(orgId: Uuid, slot: AccountSlot) {
        database.inOrg(orgId) { write { slotRelease(orgId, slot.id) } }
    }

    override suspend fun renew(orgId: Uuid, slot: AccountSlot) {
        database.inOrg(orgId) { write { slotRenew(orgId, slot.id) } }
    }

    override suspend fun active(orgId: Uuid): Map<Uuid, Int> = database.inOrg(orgId) { counts(slotsActive(orgId)) }

    override suspend fun activeSessions(orgId: Uuid, within: Duration): List<ActiveSession> = database.inOrg(orgId) {
        val f = SharingAffinities
        affinitiesActive(orgId, AccountSlotRepository.AFFINITY - within).map {
            ActiveSession(it[f.memberId], it[f.sessionHash], it[f.accountId])
        }
    }
}

private val ACTIVE: Count = SharingSlots.id.count()

private fun counts(query: Query): Map<Uuid, Int> = query.associate { it[SharingSlots.accountId] to it[ACTIVE].toInt() }

/**
 * A session already remembered is renewed; a new one is remembered while the member and the org are under their
 * bounds, so that one member never evicts another's sessions by minting ids (TeamCode's bounds).
 */
private fun mayRemember(orgId: Uuid, memberId: Uuid, hash: String): Boolean {
    if (!affinityKnown(orgId, memberId, hash).empty()) return true
    val member = memberAffinities(orgId, memberId).single()[AFFINITIES]
    val org = orgAffinities(orgId).single()[AFFINITIES]
    return member < AccountSlotRepository.MAX_MEMBER_AFFINITIES && org < AccountSlotRepository.MAX_ORG_AFFINITIES
}

private val AFFINITIES: Count = SharingAffinities.sessionHash.count()

/** The session's affinity, live or not. */
internal fun affinityKnown(orgId: Uuid, memberId: Uuid, hash: String): Query = SharingAffinities
    .select(SharingAffinities.sessionHash)
    .where { own(orgId, memberId) and (SharingAffinities.sessionHash eq hash) }

/** How many sessions the member has remembered. */
internal fun memberAffinities(orgId: Uuid, memberId: Uuid): Query =
    SharingAffinities.select(AFFINITIES).where { own(orgId, memberId) }

/** How many live sessions the organization has remembered. */
internal fun orgAffinities(orgId: Uuid): Query = SharingAffinities.select(AFFINITIES)
    .where { (SharingAffinities.orgId eq orgId) and (SharingAffinities.expiresAt greater databaseNow()) }

private fun own(orgId: Uuid, memberId: Uuid) = (SharingAffinities.orgId eq orgId) and
    (SharingAffinities.memberId eq memberId)

/** A session id's digest, as stored: the hex of its UTF-8 bytes' SHA-256. */
internal fun sessionHash(session: String): String =
    MessageDigest.getInstance("SHA-256").digest(session.encodeToByteArray()).joinToString("") { "%02x".format(it) }

/** The rows of the candidates still serving, locked in id order. */
internal fun slotLock(orgId: Uuid, ids: List<Uuid>): Query {
    val a = SharingAccounts
    return a.select(a.id)
        .where {
            (a.orgId eq orgId) and (a.id eq anyFrom(ids, UuidColumnType())) and (a.status eq READY) and
                (a.ownership neq RefreshOwnership.PENDING_HANDOVER.name)
        }
        .orderBy(a.id to SortOrder.ASC)
        .forUpdate()
}

/** The unexpired slots of the org, or of the candidates [ids] when given. */
internal fun slotsActive(orgId: Uuid, ids: List<Uuid>? = null): Query {
    val s = SharingSlots
    return s.select(s.accountId, ACTIVE)
        .where {
            val live = (s.orgId eq orgId) and (s.expiresAt greater databaseNow())
            if (ids == null) live else live and (s.accountId eq anyFrom(ids, UuidColumnType()))
        }
        .groupBy(s.accountId)
}

/** The account a member's session used last, while its affinity lasts. */
internal fun slotRemembered(orgId: Uuid, memberId: Uuid, hash: String): Query {
    val f = SharingAffinities
    return f.select(f.accountId)
        .where { own(orgId, memberId) and (f.sessionHash eq hash) and (f.expiresAt greater databaseNow()) }
}

/** The org's expired slots. */
internal fun StatementBuilder.slotPurge(orgId: Uuid): DeleteStatement =
    SharingSlots.deleteWhere { (SharingSlots.orgId eq orgId) and (SharingSlots.expiresAt lessEq databaseNow()) }

/** A new slot, expiring after its lease. */
internal fun StatementBuilder.slotInsert(orgId: Uuid, slot: AccountSlot): InsertStatement<Number> =
    SharingSlots.insert {
        it[id] = slot.id
        it[this.orgId] = orgId
        it[accountId] = slot.accountId
        it[expiresAt] = databaseNowPlus(AccountSlotRepository.SLOT_LEASE)
    }

/**
 * The sessions leased within a span: their affinity, renewed for [AccountSlotRepository.AFFINITY] at each lease, ends
 * more than [left] (the affinity minus the span) from now. By the expiry index, at most the org's bound.
 */
internal fun affinitiesActive(orgId: Uuid, left: Duration): Query {
    val f = SharingAffinities
    return f.select(f.memberId, f.sessionHash, f.accountId)
        .where { (f.orgId eq orgId) and (f.expiresAt greater databaseNowPlus(left.inWholeSeconds)) }
        .limit(AccountSlotRepository.MAX_ORG_AFFINITIES)
}

/** A slot still held, extended by its lease from now. */
internal fun StatementBuilder.slotRenew(orgId: Uuid, id: Uuid): UpdateStatement =
    SharingSlots.update({ (SharingSlots.orgId eq orgId) and (SharingSlots.id eq id) }) {
        it[expiresAt] = databaseNowPlus(AccountSlotRepository.SLOT_LEASE)
    }

/** One slot given back. */
internal fun StatementBuilder.slotRelease(orgId: Uuid, id: Uuid): DeleteStatement =
    SharingSlots.deleteWhere { (SharingSlots.orgId eq orgId) and (SharingSlots.id eq id) }

/** The org's expired affinities. */
internal fun StatementBuilder.affinityPurge(orgId: Uuid): DeleteStatement = SharingAffinities.deleteWhere {
    (SharingAffinities.orgId eq orgId) and (SharingAffinities.expiresAt lessEq databaseNow())
}

/** The session remembered on [accountId], or renewed there. */
internal fun StatementBuilder.affinityRemember(
    orgId: Uuid,
    memberId: Uuid,
    hash: String,
    accountId: Uuid,
): UpsertStatement<Long> = SharingAffinities.upsert(
    SharingAffinities.orgId,
    SharingAffinities.memberId,
    SharingAffinities.sessionHash,
) {
    it[this.orgId] = orgId
    it[this.memberId] = memberId
    it[sessionHash] = hash
    it[this.accountId] = accountId
    it[expiresAt] = databaseNowPlus(AccountSlotRepository.AFFINITY)
}

private const val READY = "READY"
