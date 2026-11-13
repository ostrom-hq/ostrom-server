// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.mcprelay.infrastructure

import run.ostrom.server.mcprelay.domain.McpCallSlot
import run.ostrom.server.mcprelay.domain.McpCallSlotRepository
import run.ostrom.server.mcprelay.domain.McpUsage
import run.ostrom.server.secrets.infrastructure.executeInOrg
import run.ostrom.server.secrets.infrastructure.inTenant
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import kotlin.uuid.Uuid

/**
 * [McpCallSlotRepository] on PostgreSQL ([McpRelayMigrations] version 6). An admission locks the server's row
 * (`FOR UPDATE`), so two replicas admitting on one server take turns and never pass a limit together; it drops the
 * server's expired slots and past minutes as it goes, so neither table grows with dead rows.
 *
 * @param driver The database.
 */
class McpCallSlotRepositoryImpl(private val driver: Driver) : McpCallSlotRepository {
    override suspend fun acquire(
        orgId: Uuid,
        memberId: Uuid,
        serverId: Uuid,
        admit: (McpUsage) -> Boolean,
    ): McpCallSlot? = driver.inTenant(orgId) {
        fun sql(text: String) = Statement.create(text).bind("org_id", orgId).bind("server_id", serverId)
        val present = fetchAll(sql(MCP_SLOT_LOCK_SQL)).getOrThrow().firstOrNull() != null
        if (!present) return@inTenant null
        execute(sql(MCP_SLOT_PURGE_SQL)).getOrThrow()
        execute(sql(MCP_MINUTE_PURGE_SQL)).getOrThrow()
        val usage = fetchAll(sql(MCP_USAGE_SQL).bind("member_id", memberId)).getOrThrow().single().let {
            McpUsage(
                memberRunning = it.get("member_running").asLong().toInt(),
                serverRunning = it.get("server_running").asLong().toInt(),
                memberThisMinute = it.get("member_minute").asLong().toInt(),
                serverThisMinute = it.get("server_minute").asLong().toInt(),
            )
        }
        if (!admit(usage)) return@inTenant null
        val slot = McpCallSlot(Uuid.random())
        execute(sql(MCP_SLOT_INSERT_SQL).bind("id", slot.id).bind("member_id", memberId)).getOrThrow()
        execute(sql(MCP_MINUTE_COUNT_SQL).bind("member_id", memberId)).getOrThrow()
        slot
    }

    override suspend fun release(orgId: Uuid, slot: McpCallSlot) {
        driver.executeInOrg(orgId, Statement.create(MCP_SLOT_RELEASE_SQL).bind("org_id", orgId).bind("id", slot.id))
            .getOrThrow()
    }
}

private val SLOT_SECONDS = McpCallSlotRepository.SLOT_LEASE.inWholeSeconds

/** The current minute since the epoch, by the database's clock. */
private const val MINUTE = "CAST(FLOOR(EXTRACT(EPOCH FROM now()) / 60) AS BIGINT)"

/** The server's row, locked: admissions on one server take turns. */
internal const val MCP_SLOT_LOCK_SQL =
    "SELECT id FROM mcp_relay_servers WHERE org_id = :org_id AND id = :server_id FOR UPDATE"

/** The server's expired slots. */
internal const val MCP_SLOT_PURGE_SQL =
    "DELETE FROM mcp_relay_slots WHERE org_id = :org_id AND server_id = :server_id AND expires_at <= now()"

/** The server's past minutes. */
internal const val MCP_MINUTE_PURGE_SQL =
    "DELETE FROM mcp_relay_minutes WHERE org_id = :org_id AND server_id = :server_id AND minute < $MINUTE"

/** What the member and the server run now and started this minute. */
internal const val MCP_USAGE_SQL =
    "SELECT (SELECT count(*) FROM mcp_relay_slots WHERE org_id = :org_id AND server_id = :server_id " +
        "AND member_id = :member_id) AS member_running, " +
        "(SELECT count(*) FROM mcp_relay_slots WHERE org_id = :org_id AND server_id = :server_id) AS server_running, " +
        "(SELECT COALESCE(sum(calls), 0) FROM mcp_relay_minutes WHERE org_id = :org_id AND server_id = :server_id " +
        "AND minute = $MINUTE AND member_id = :member_id) AS member_minute, " +
        "(SELECT COALESCE(sum(calls), 0) FROM mcp_relay_minutes WHERE org_id = :org_id AND server_id = :server_id " +
        "AND minute = $MINUTE) AS server_minute"

/** A new slot, expiring after its lease. */
internal val MCP_SLOT_INSERT_SQL =
    "INSERT INTO mcp_relay_slots (id, org_id, server_id, member_id, expires_at) " +
        "VALUES (:id, :org_id, :server_id, :member_id, now() + INTERVAL '$SLOT_SECONDS seconds')"

/** One more operation of the member on the server this minute. */
internal const val MCP_MINUTE_COUNT_SQL =
    "INSERT INTO mcp_relay_minutes (org_id, server_id, minute, member_id, calls) " +
        "VALUES (:org_id, :server_id, $MINUTE, :member_id, 1) " +
        "ON CONFLICT (server_id, minute, member_id) DO UPDATE SET calls = mcp_relay_minutes.calls + 1"

/** One slot given back. */
internal const val MCP_SLOT_RELEASE_SQL = "DELETE FROM mcp_relay_slots WHERE org_id = :org_id AND id = :id"
