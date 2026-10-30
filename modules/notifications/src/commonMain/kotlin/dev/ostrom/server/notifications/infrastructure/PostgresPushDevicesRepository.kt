// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.infrastructure

import dev.ostrom.server.notifications.domain.PushDevice
import dev.ostrom.server.notifications.domain.PushDevicesRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.uuid.Uuid

/**
 * [PushDevicesRepository] on PostgreSQL (sqlx4k): every call is one transaction that first sets the organization read
 * by the row level security policy.
 *
 * @param driver The connection pool.
 */
class PostgresPushDevicesRepository(private val driver: Driver) : PushDevicesRepository {
    private suspend fun <T> scoped(orgId: Uuid, block: suspend QueryExecutor.() -> T): T = driver.transaction {
        rows(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", orgId.toString()))
        block()
    }

    override suspend fun devices(orgId: Uuid, personId: String): List<PushDevice> = scoped(orgId) {
        rows(
            Statement.create(
                "SELECT $COLUMNS FROM notifications_push_devices WHERE org_id = :org AND person_id = :person " +
                    "ORDER BY created_at, id LIMIT $MAX_DEVICES",
            ).bind("org", orgId).bind("person", personId),
        ).map { it.toDevice() }
    }

    override suspend fun save(device: PushDevice): PushDevice = scoped(device.orgId) {
        // A token registered again (a reinstall, another member signing in on the phone) keeps its row.
        rows(
            Statement.create(
                "INSERT INTO notifications_push_devices ($COLUMNS) " +
                    "VALUES (:org, :id, :person, :token, :provider, :platform, :locale, :at) " +
                    "ON CONFLICT (org_id, push_token) DO UPDATE SET person_id = EXCLUDED.person_id, " +
                    "platform = EXCLUDED.platform, locale = EXCLUDED.locale RETURNING $COLUMNS",
            ).bind("org", device.orgId).bind("id", device.id).bind("person", device.personId)
                .bind("token", device.pushToken).bind("provider", device.provider).bind("platform", device.platform)
                .bind("locale", device.locale).bind("at", device.createdAt),
        ).first().toDevice()
    }

    override suspend fun delete(orgId: Uuid, personId: String, id: Uuid): Boolean = scoped(orgId) {
        execute(
            Statement.create(
                "DELETE FROM notifications_push_devices WHERE org_id = :org AND person_id = :person AND id = :id",
            ).bind("org", orgId).bind("person", personId).bind("id", id),
        ).getOrThrow() > 0
    }

    private companion object {
        const val MAX_DEVICES = 20
        const val COLUMNS = "org_id, id, person_id, push_token, provider, platform, locale, created_at"

        suspend fun QueryExecutor.rows(statement: Statement): List<ResultSet.Row> =
            fetchAll(statement).getOrThrow().toList()

        fun ResultSet.Row.toDevice() = PushDevice(
            id = get("id").asUuid(),
            orgId = get("org_id").asUuid(),
            personId = get("person_id").asString(),
            pushToken = get("push_token").asString(),
            provider = get("provider").asString(),
            platform = get("platform").asString(),
            locale = get("locale").asStringOrNull(),
            createdAt = get("created_at").asInstant(),
        )
    }
}
