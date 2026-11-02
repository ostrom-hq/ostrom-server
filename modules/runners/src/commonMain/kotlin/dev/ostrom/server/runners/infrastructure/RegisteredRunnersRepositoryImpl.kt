// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.infrastructure

import dev.ostrom.server.runners.domain.RegisteredRunner
import dev.ostrom.server.runners.domain.RegisteredRunnersRepository
import dev.ostrom.server.runners.domain.RunnerRegistrationOutcome
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

/**
 * [RegisteredRunnersRepository] on PostgreSQL (`runners_registered`, migration 2 of [RunnersMigrations]): every read
 * and write runs confined to the runner's organization ([inTenant], S2-01). The labels are a JSON array of strings.
 *
 * @param driver The database.
 */
class RegisteredRunnersRepositoryImpl(private val driver: Driver) : RegisteredRunnersRepository {
    override suspend fun register(runner: RegisteredRunner, max: Int): RunnerRegistrationOutcome =
        driver.inTenant(runner.orgId) {
            // The same per-organization lock as the cloud sessions' cap, under its own key.
            fetchAll(
                Statement.create("SELECT pg_advisory_xact_lock(hashtext(:key))")
                    .bind("key", "runners-registered:${runner.orgId}"),
            ).getOrThrow()
            val count = fetchAll(
                Statement.create(COUNT_SQL).bind("org_id", runner.orgId),
            ).getOrThrow().first().get("n").asLong()
            if (count >= max) return@inTenant RunnerRegistrationOutcome.LIMIT_REACHED
            val inserted = execute(
                Statement.create(
                    """
                    INSERT INTO runners_registered ($COLUMNS) VALUES (:id, :org_id, :name, :labels, :version,
                    :key_hash, :registered_by, :created_at, :last_seen_at) ON CONFLICT (org_id, lower(name)) DO NOTHING
                    """,
                ).bind("id", runner.id).bind("org_id", runner.orgId).bind("name", runner.name)
                    .bind("labels", Json.encodeToString(LABELS, runner.labels)).bind("version", runner.version)
                    .bind("key_hash", runner.keyHash).bind("registered_by", runner.registeredBy)
                    .bind("created_at", runner.createdAt).bind("last_seen_at", runner.lastSeenAt),
            ).getOrThrow()
            if (inserted == 0L) RunnerRegistrationOutcome.NAME_TAKEN else RunnerRegistrationOutcome.REGISTERED
        }

    override suspend fun list(orgId: Uuid, limit: Int): List<RegisteredRunner> = driver.inTenant(orgId) {
        fetchAll(Statement.create(LIST_SQL).bind("org_id", orgId).bind("limit", limit)).getOrThrow()
            .map { it.toRunner() }
    }

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean = driver.inTenant(orgId) {
        execute(
            Statement.create("DELETE FROM runners_registered WHERE org_id = :org_id AND id = :id")
                .bind("org_id", orgId).bind("id", id),
        ).getOrThrow() > 0
    }

    private fun ResultSet.Row.toRunner() = RegisteredRunner(
        id = get("id").asUuid(),
        orgId = get("org_id").asUuid(),
        name = get("name").asString(),
        labels = Json.decodeFromString(LABELS, get("labels").asString()),
        version = get("version").asStringOrNull(),
        keyHash = get("key_hash").asString(),
        registeredBy = get("registered_by").asString(),
        createdAt = get("created_at").asInstant(),
        lastSeenAt = get("last_seen_at").asInstantOrNull(),
    )

    /** The columns and the request-path reads, shared with the query plan test. */
    internal companion object {
        const val COLUMNS: String =
            "id, org_id, name, labels, version, key_hash, registered_by, created_at, last_seen_at"

        /** An organization's runners by name whatever its case, through `runners_registered_name`. */
        const val LIST_SQL: String =
            "SELECT $COLUMNS FROM runners_registered WHERE org_id = :org_id ORDER BY lower(name) LIMIT :limit"

        /** How many runners an organization has, through `runners_registered_name`. */
        const val COUNT_SQL: String = "SELECT COUNT(*) AS n FROM runners_registered WHERE org_id = :org_id"

        private val LABELS = ListSerializer(String.serializer())
    }
}
