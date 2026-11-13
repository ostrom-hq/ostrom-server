// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.RegisteredRunner
import run.ostrom.server.runners.domain.RegisteredRunnerKey
import run.ostrom.server.runners.domain.RegisteredRunnersRepository
import run.ostrom.server.runners.domain.RunnerRegistrationOutcome
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstantOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asIntOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [RegisteredRunnersRepository] on PostgreSQL (`runners_registered`, migrations 2 and 4 of [RunnersMigrations]):
 * every read and write runs confined to the runner's organization ([inTenant], S2-01), except [keyOf]. The
 * labels are a JSON array of strings.
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
            // No capacity yet: a runner declares it at its heartbeats.
            val inserted = execute(
                Statement.create(
                    """
                    INSERT INTO runners_registered ($COLUMNS) VALUES (:id, :org_id, :name, :labels, :version,
                    :key_hash, :registered_by, :created_at, :last_seen_at, NULL)
                    ON CONFLICT (org_id, lower(name)) DO NOTHING
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

    // The one read across organizations, through `runners_registered_key` (owned by `ostrom_router`, migration 4): the
    // serving role itself, never inside an organization's transaction.
    override suspend fun keyOf(id: Uuid): RegisteredRunnerKey? = driver.fetchAll(
        Statement.create(KEY_SQL).bind("id", id),
    ).getOrThrow().firstOrNull()?.let { RegisteredRunnerKey(it.get("org_id").asUuid(), it.get("key_hash").asString()) }

    override suspend fun touch(orgId: Uuid, id: Uuid, keyHash: String, at: Instant, capacity: Int?): Boolean =
        driver.inTenant(orgId) {
            execute(
                Statement.create(TOUCH_SQL).bind("at", at).bind("org_id", orgId).bind("id", id)
                    .bind("key_hash", keyHash)
                    .let {
                        if (capacity == null) it.bindNull("capacity", Int::class) else it.bind("capacity", capacity)
                    },
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
        capacity = get("capacity").asIntOrNull(),
    )

    /** The columns and the request-path reads, shared with the query plan test. */
    internal companion object {
        const val COLUMNS: String =
            "id, org_id, name, labels, version, key_hash, registered_by, created_at, last_seen_at, capacity"

        /** An organization's runners by name whatever its case, through `runners_registered_name`. */
        const val LIST_SQL: String =
            "SELECT $COLUMNS FROM runners_registered WHERE org_id = :org_id ORDER BY lower(name) LIMIT :limit"

        /** How many runners an organization has, through `runners_registered_name`. */
        const val COUNT_SQL: String = "SELECT COUNT(*) AS n FROM runners_registered WHERE org_id = :org_id"

        /** A runner's organization and key hash by its id, through `runners_registered_key` (its primary key). */
        const val KEY_SQL: String = "SELECT org_id, key_hash FROM runners_registered_key(CAST(:id AS UUID))"

        /**
         * A sign of life and the capacity it declares, written only while the runner has that key; GREATEST keeps the
         * newest (it skips NULL).
         */
        const val TOUCH_SQL: String =
            "UPDATE runners_registered SET last_seen_at = GREATEST(last_seen_at, :at), " +
                "capacity = CAST(:capacity AS INTEGER) " +
                "WHERE org_id = :org_id AND id = :id AND key_hash = :key_hash"

        private val LABELS = ListSerializer(String.serializer())
    }
}
