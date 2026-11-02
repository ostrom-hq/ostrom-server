// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.infrastructure

import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.launches.domain.models.Launch
import dev.ostrom.server.launches.domain.repositories.LaunchesRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.uuid.Uuid

/**
 * [LaunchesRepository] on PostgreSQL (the table of [LaunchesMigrations]), every query in a transaction scoped to one
 * organization by `ostrom.org_id`, which row level security enforces.
 *
 * @param driver The connection pool.
 */
class PostgresLaunchesRepository(private val driver: Driver) : LaunchesRepository {
    override suspend fun create(launch: Launch) {
        driver.withOrganization(launch.orgId) { tx ->
            tx.execute(
                Statement.create("INSERT INTO launches_launches ($COLUMNS) VALUES ($VALUES)")
                    .bind("org_id", launch.orgId)
                    .bind("id", launch.id)
                    .bind("user_id", launch.userId)
                    .bind("dev", launch.dev)
                    .nullable("repo", launch.repo)
                    .bind("agent", launch.agent)
                    .nullable("model", launch.model)
                    .bind("created_at", launch.createdAt)
                    .bind("expires_at", launch.expiresAt),
            ).getOrThrow()
        }
    }

    override suspend fun find(orgId: Uuid, id: String): Launch? = driver.withOrganization(orgId) { tx ->
        tx.fetchAll(Statement.create(FIND).bind("org_id", orgId).bind("id", id)).getOrThrow()
            .firstOrNull()?.toLaunch()
    }

    private fun Statement.nullable(name: String, value: String?): Statement =
        if (value == null) bindNull(name, String::class) else bind(name, value)

    private fun ResultSet.Row.toLaunch() = Launch(
        id = get("id").asString(),
        orgId = get("org_id").asUuid(),
        userId = get("user_id").asUuid(),
        dev = get("dev").asString(),
        repo = get("repo").asStringOrNull(),
        agent = get("agent").asString(),
        model = get("model").asStringOrNull(),
        createdAt = get("created_at").asInstant(),
        expiresAt = get("expires_at").asInstant(),
    )

    /** The SQL of the repository, also read by the query plans test. */
    companion object {
        private val COLUMN_NAMES =
            listOf("org_id", "id", "user_id", "dev", "repo", "agent", "model", "created_at", "expires_at")
        private val COLUMNS = COLUMN_NAMES.joinToString(", ")
        private val VALUES = COLUMN_NAMES.joinToString(", ") { ":$it" }

        /** A launch by its primary key. */
        val FIND: String = "SELECT $COLUMNS FROM launches_launches WHERE org_id = :org_id AND id = :id"
    }
}
