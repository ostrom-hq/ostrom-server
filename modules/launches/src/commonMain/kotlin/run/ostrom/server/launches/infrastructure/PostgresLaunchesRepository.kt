// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches.infrastructure

import run.ostrom.server.kernel.database.PurgeDates
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.launches.domain.models.Launch
import run.ostrom.server.launches.domain.models.LaunchLimit
import run.ostrom.server.launches.domain.repositories.LaunchesRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuidOrNull
import kotlin.time.Duration
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [LaunchesRepository] on PostgreSQL (the table of [LaunchesMigrations]), every query in a transaction scoped to one
 * organization by `ostrom.org_id`, which row level security enforces.
 *
 * @param driver The connection pool.
 */
class PostgresLaunchesRepository(
    private val driver: Driver,
    private val clock: Clock = Clock.System,
) : LaunchesRepository {
    override suspend fun create(launch: Launch, limit: LaunchLimit): Duration? =
        driver.withOrganization(launch.orgId) { tx -> limited(tx, launch, limit) }

    // The one deletion across organizations, through the function of LaunchesMigrations versions 4 and 5, which
    // bounds the date to the retention itself.
    override suspend fun purge(before: Instant): Long {
        PurgeDates.check(before, clock.now())
        return driver.fetchAll(
            Statement.create("SELECT launches_purge(CAST(:before AS TIMESTAMPTZ)) AS n").bind("before", before),
        ).getOrThrow().first().get("n").asLong()
    }

    private suspend fun limited(tx: Transaction, launch: Launch, limit: LaunchLimit): Duration? {
        // One launch of the organization at a time: the count below then holds until the insert commits.
        tx.fetchAll(Statement.create(LOCK).bind("org_id", launch.orgId)).getOrThrow()
        val since = launch.createdAt - limit.window
        val person = tx.window(Statement.create(PERSON_WINDOW).bind("user_id", launch.userId), launch.orgId, since)
        val org = tx.window(Statement.create(ORG_WINDOW), launch.orgId, since)
        // A place frees up when the oldest launch counted in each full window has left it.
        val freesAt = listOfNotNull(
            person.oldest.takeIf { person.count >= limit.perPerson },
            org.oldest.takeIf { org.count >= limit.perOrg },
        ).maxOrNull()
        if (freesAt != null) return freesAt + limit.window - launch.createdAt
        insert(tx, launch)
        return null
    }

    private suspend fun insert(tx: Transaction, launch: Launch) {
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
                .bind("expires_at", launch.expiresAt)
                .cloud(launch.cloudSessionId)
                .nullable("workflow_id", launch.workflowId),
        ).getOrThrow()
    }

    /** The launches counted in a window, and the oldest of them (the window's start when none). */
    private class Window(val count: Int, val oldest: Instant)

    private suspend fun Transaction.window(statement: Statement, orgId: Uuid, since: Instant): Window {
        val row = fetchAll(statement.bind("org_id", orgId).bind("since", since)).getOrThrow().first()
        val count = row.get("n").asString().toInt()
        return Window(count, if (count == 0) since else row.get("oldest").asInstant())
    }

    override suspend fun find(orgId: Uuid, id: String): Launch? = driver.withOrganization(orgId) { tx ->
        tx.fetchAll(Statement.create(FIND).bind("org_id", orgId).bind("id", id)).getOrThrow()
            .firstOrNull()?.toLaunch()
    }

    private fun Statement.nullable(name: String, value: String?): Statement =
        if (value == null) bindNull(name, String::class) else bind(name, value)

    private fun Statement.cloud(value: Uuid?): Statement =
        if (value == null) bindNull("cloud_session_id", Uuid::class) else bind("cloud_session_id", value)

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
        cloudSessionId = get("cloud_session_id").asUuidOrNull(),
        workflowId = get("workflow_id").asStringOrNull(),
    )

    /** The SQL of the repository, also read by the query plans test. */
    companion object {
        private val COLUMN_NAMES = listOf(
            "org_id", "id", "user_id", "dev", "repo", "agent", "model", "created_at", "expires_at",
            "cloud_session_id", "workflow_id",
        )
        private val COLUMNS = COLUMN_NAMES.joinToString(", ")
        private val VALUES = COLUMN_NAMES.joinToString(", ") { ":$it" }

        /** Serializes the launches of one organization within a transaction (released at its end). */
        const val LOCK: String =
            "SELECT pg_advisory_xact_lock(hashtextextended('launches:' || CAST(:org_id AS TEXT), 0))"

        /** The launches of one person in one organization since an instant: the index on its creations. */
        const val PERSON_WINDOW: String = "SELECT COUNT(*) AS n, MIN(created_at) AS oldest FROM launches_launches " +
            "WHERE org_id = :org_id AND user_id = :user_id AND created_at > :since"

        /** The launches of one organization since an instant. */
        const val ORG_WINDOW: String = "SELECT COUNT(*) AS n, MIN(created_at) AS oldest FROM launches_launches " +
            "WHERE org_id = :org_id AND created_at > :since"

        /** A launch by its primary key. */
        val FIND: String = "SELECT $COLUMNS FROM launches_launches WHERE org_id = :org_id AND id = :id"
    }
}
