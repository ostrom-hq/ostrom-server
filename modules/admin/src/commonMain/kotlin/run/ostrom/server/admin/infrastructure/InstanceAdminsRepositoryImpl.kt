// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin.infrastructure

import run.ostrom.server.admin.domain.AdminSource
import run.ostrom.server.admin.domain.InstanceAdmin
import run.ostrom.server.admin.domain.InstanceAdminsRepository
import run.ostrom.server.admin.domain.RemoveOutcome
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** [InstanceAdminsRepository] on PostgreSQL (`admin_instance_admins`, the instance's, no organization). */
class InstanceAdminsRepositoryImpl(private val driver: Driver) : InstanceAdminsRepository {
    override suspend fun list(): List<InstanceAdmin> = driver.fetchAll(
        "SELECT $COLUMNS FROM admin_instance_admins WHERE removed_at IS NULL ORDER BY added_at, user_id",
    ).getOrThrow().map { it.toAdmin() }

    override suspend fun isAdmin(userId: Uuid): Boolean = driver.fetchAll(
        Statement.create("SELECT 1 FROM admin_instance_admins WHERE user_id = :user AND removed_at IS NULL")
            .bind("user", userId),
    ).getOrThrow().firstOrNull() != null

    override suspend fun addIfNeverSeen(admin: InstanceAdmin): Boolean = driver.execute(
        insert(admin, "ON CONFLICT (user_id) DO NOTHING"),
    ).getOrThrow() == 1L

    override suspend fun add(admin: InstanceAdmin): Boolean = driver.execute(
        insert(
            admin,
            "ON CONFLICT (user_id) DO UPDATE SET source = EXCLUDED.source, added_at = EXCLUDED.added_at, " +
                "added_by = EXCLUDED.added_by, removed_at = NULL, removed_by = NULL " +
                "WHERE admin_instance_admins.removed_at IS NOT NULL",
        ),
    ).getOrThrow() == 1L

    override suspend fun remove(userId: Uuid, by: Uuid, at: Instant): RemoveOutcome = driver.transaction {
        // The current administrators locked first: two removals at once cannot leave none.
        val current = fetchAll("SELECT user_id FROM admin_instance_admins WHERE removed_at IS NULL FOR UPDATE")
            .getOrThrow().map { it.get("user_id").asUuid() }
        when {
            userId !in current -> RemoveOutcome.NOT_FOUND
            current.size == 1 -> RemoveOutcome.LAST
            else -> {
                execute(
                    Statement.create(
                        "UPDATE admin_instance_admins SET removed_at = :at, removed_by = :by WHERE user_id = :user",
                    ).bind("at", at).bind("by", by).bind("user", userId),
                ).getOrThrow()
                RemoveOutcome.REMOVED
            }
        }
    }

    private fun insert(admin: InstanceAdmin, conflict: String) = Statement.create(
        "INSERT INTO admin_instance_admins (user_id, source, added_at, added_by) " +
            "VALUES (:user, :source, :at, :by) $conflict",
    ).bind("user", admin.userId).bind("source", admin.source.wire).bind("at", admin.addedAt).bind("by", admin.addedBy)

    private fun ResultSet.Row.toAdmin() = InstanceAdmin(
        userId = get("user_id").asUuid(),
        source = AdminSource.of(get("source").asString()),
        addedAt = get("added_at").asInstant(),
        addedBy = get("added_by").asUuidOrNull(),
    )

    private companion object {
        const val COLUMNS = "user_id, source, added_at, added_by"
    }
}
