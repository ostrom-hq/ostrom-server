// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.secrets.infrastructure.executeInOrg
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.secrets.infrastructure.inTenant
import run.ostrom.server.sharing.domain.AccountSlot
import run.ostrom.server.sharing.domain.AccountSlotRepository
import run.ostrom.server.sharing.domain.SharedAccount
import run.ostrom.server.sharing.domain.SlotView
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.uuid.Uuid

/**
 * [AccountSlotRepository] on PostgreSQL ([SharingMigrations] version 8). A take locks the candidates' rows
 * (`FOR UPDATE`, in id order so that two takes never deadlock), counts their unexpired slots, and inserts the slot in
 * the same transaction: two replicas never fill an account past its limit together. Expiry is the database's clock.
 * A session id is stored as its SHA-256 digest only. The org's expired slots and affinities are deleted as a take goes,
 * so neither table keeps an org's dead rows past its next lease.
 *
 * @param driver The database.
 */
class AccountSlotRepositoryImpl(private val driver: Driver) : AccountSlotRepository {
    override suspend fun take(
        orgId: Uuid,
        memberId: Uuid,
        session: String?,
        candidates: List<SharedAccount>,
        pick: (SlotView) -> SharedAccount?,
    ): AccountSlot? = driver.inTenant(orgId) {
        fun sql(text: String) = Statement.create(text).bind("org_id", orgId)
        val ids = candidates.joinToString(",") { it.id.toString() }
        val serving = fetchAll(sql(SLOT_LOCK_SQL).bind("ids", ids)).getOrThrow().map { it.get("id").asUuid() }.toSet()
        val active = fetchAll(sql(SLOT_ACTIVE_OF_SQL).bind("ids", ids)).getOrThrow().counts()
        val remembered = session?.let {
            fetchAll(sql(SLOT_REMEMBERED_SQL).bind("member_id", memberId).bind("session", it)).getOrThrow()
                .firstOrNull()?.let { row -> row.get("account_id").asUuid() }
        }
        pick(SlotView(active, remembered, serving))?.let { account ->
            val slot = AccountSlot(Uuid.random(), account.id)
            execute(sql(SLOT_PURGE_SQL)).getOrThrow()
            execute(sql(SLOT_INSERT_SQL).bind("id", slot.id).bind("account_id", account.id)).getOrThrow()
            if (session != null) {
                execute(sql(AFFINITY_PURGE_SQL)).getOrThrow()
                execute(
                    sql(AFFINITY_REMEMBER_SQL)
                        .bind("member_id", memberId)
                        .bind("account_id", account.id)
                        .bind("session", session),
                ).getOrThrow()
            }
            slot
        }
    }

    override suspend fun release(orgId: Uuid, slot: AccountSlot) {
        driver.executeInOrg(
            orgId,
            Statement.create(SLOT_RELEASE_SQL).bind("org_id", orgId).bind("id", slot.id),
        ).getOrThrow()
    }

    override suspend fun renew(orgId: Uuid, slot: AccountSlot) {
        driver.executeInOrg(
            orgId,
            Statement.create(SLOT_RENEW_SQL).bind("org_id", orgId).bind("id", slot.id),
        ).getOrThrow()
    }

    override suspend fun active(orgId: Uuid): Map<Uuid, Int> = driver.fetchInOrg(
        orgId,
        Statement.create(SLOT_ACTIVE_SQL).bind("org_id", orgId),
    ).getOrThrow().counts()

    private fun ResultSet.counts(): Map<Uuid, Int> =
        associate { it.get("account_id").asUuid() to it.get("active").asLong().toInt() }
}

private val SLOT_SECONDS = AccountSlotRepository.SLOT_LEASE.inWholeSeconds
private val AFFINITY_SECONDS = AccountSlotRepository.AFFINITY.inWholeSeconds
private const val IDS = "ANY(CAST(string_to_array(:ids, ',') AS UUID[]))"
private const val HASH = "encode(sha256(convert_to(:session, 'UTF8')), 'hex')"

/** The rows of the candidates still serving, locked in id order. */
internal const val SLOT_LOCK_SQL =
    "SELECT id FROM sharing_accounts WHERE org_id = :org_id AND id = $IDS AND status = 'READY' " +
        "ORDER BY id FOR UPDATE"

/** The unexpired slots of the candidates. */
internal const val SLOT_ACTIVE_OF_SQL =
    "SELECT account_id, count(*) AS active FROM sharing_slots " +
        "WHERE org_id = :org_id AND account_id = $IDS AND expires_at > now() GROUP BY account_id"

/** The account a member's session used last, while its affinity lasts. */
internal const val SLOT_REMEMBERED_SQL =
    "SELECT account_id FROM sharing_affinities WHERE org_id = :org_id AND member_id = :member_id " +
        "AND session_hash = $HASH AND expires_at > now()"

/** The org's expired slots. */
internal const val SLOT_PURGE_SQL = "DELETE FROM sharing_slots WHERE org_id = :org_id AND expires_at <= now()"

/** A new slot, expiring after its lease. */
internal val SLOT_INSERT_SQL =
    "INSERT INTO sharing_slots (id, org_id, account_id, expires_at) " +
        "VALUES (:id, :org_id, :account_id, now() + INTERVAL '$SLOT_SECONDS seconds')"

/** The unexpired slots of the org. */
internal const val SLOT_ACTIVE_SQL =
    "SELECT account_id, count(*) AS active FROM sharing_slots WHERE org_id = :org_id AND expires_at > now() " +
        "GROUP BY account_id"

/** A slot still held, extended by its lease from now. */
internal val SLOT_RENEW_SQL =
    "UPDATE sharing_slots SET expires_at = now() + INTERVAL '$SLOT_SECONDS seconds' WHERE org_id = :org_id AND id = :id"

/** One slot given back. */
internal const val SLOT_RELEASE_SQL = "DELETE FROM sharing_slots WHERE org_id = :org_id AND id = :id"

/** The org's expired affinities. */
internal const val AFFINITY_PURGE_SQL = "DELETE FROM sharing_affinities WHERE org_id = :org_id AND expires_at <= now()"

/**
 * A session already remembered is renewed; a new one is remembered while the member and the org are under their
 * bounds, so that one member never evicts another's sessions by minting ids (TeamCode's bounds).
 */
internal val AFFINITY_REMEMBER_SQL =
    "INSERT INTO sharing_affinities (org_id, member_id, session_hash, account_id, expires_at) " +
        "SELECT CAST(:org_id AS UUID), CAST(:member_id AS UUID), $HASH, CAST(:account_id AS UUID), " +
        "now() + INTERVAL '$AFFINITY_SECONDS seconds' " +
        "WHERE EXISTS (SELECT 1 FROM sharing_affinities WHERE org_id = :org_id AND member_id = :member_id " +
        "AND session_hash = $HASH) " +
        "OR ((SELECT count(*) FROM sharing_affinities WHERE org_id = :org_id AND member_id = :member_id) < " +
        "${AccountSlotRepository.MAX_MEMBER_AFFINITIES} AND (SELECT count(*) FROM sharing_affinities " +
        "WHERE org_id = :org_id AND expires_at > now()) < ${AccountSlotRepository.MAX_ORG_AFFINITIES}) " +
        "ON CONFLICT (org_id, member_id, session_hash) DO UPDATE " +
        "SET account_id = EXCLUDED.account_id, expires_at = EXCLUDED.expires_at"
