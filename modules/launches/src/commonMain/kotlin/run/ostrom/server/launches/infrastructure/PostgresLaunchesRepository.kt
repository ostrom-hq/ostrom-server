// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.PurgeDates
import run.ostrom.server.kernel.database.Read
import run.ostrom.server.kernel.database.lockKeyUntilCommit
import run.ostrom.server.launches.domain.models.Launch
import run.ostrom.server.launches.domain.models.LaunchLimit
import run.ostrom.server.launches.domain.repositories.LaunchesRepository
import run.ostrom.server.secrets.domain.LaunchCaps
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Cast
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [LaunchesRepository] on `launches_launches` (the table of [LaunchesMigrations]): every statement filters on its
 * organization and runs in it (`inOrg`), under row-level security; migration 9 grants the tenant role the table.
 *
 * @param database The serving role's pool.
 */
class PostgresLaunchesRepository(
    private val database: JdbcDatabase,
    private val clock: Clock = Clock.System,
) : LaunchesRepository {
    override suspend fun create(launch: Launch, limit: LaunchLimit): Duration? =
        database.inOrg(launch.orgId) { limited(launch, limit) }

    // The one deletion across organizations, through the function of LaunchesMigrations versions 4 and 5, which
    // bounds the date to the retention itself.
    override suspend fun purge(before: Instant): Long {
        PurgeDates.check(before, clock.now())
        return database.query { purgeRead(before).single() }
    }

    private fun JdbcTransaction.limited(launch: Launch, limit: LaunchLimit): Duration? {
        // One launch of the organization at a time: the count below then holds until the insert commits.
        lockKeyUntilCommit(launchesKey(launch.orgId))
        val since = launch.createdAt - limit.window
        val person = personWindow(launch.orgId, launch.userId, since).single()
        val org = orgWindow(launch.orgId, since).single()
        // A place frees up when the oldest launch counted in each full window has left it.
        val freesAt = listOfNotNull(
            person.oldest.takeIf { person.count >= limit.perPerson },
            org.oldest.takeIf { org.count >= limit.perOrg },
        ).maxOrNull()
        if (freesAt != null) return freesAt + limit.window - launch.createdAt
        insert(launch)
        return null
    }

    private fun insert(launch: Launch) {
        LaunchesLaunches.insert {
            it[orgId] = launch.orgId
            it[id] = launch.id
            it[userId] = launch.userId
            it[dev] = launch.dev
            it[repo] = launch.repo
            it[agent] = launch.agent
            it[model] = launch.model
            it[createdAt] = launch.createdAt
            it[expiresAt] = launch.expiresAt
            it[cloudSessionId] = launch.cloudSessionId
            it[workflowId] = launch.workflowId
            it[labels] = json.encodeToString(labelsSerializer, launch.labels)
        }
    }

    override suspend fun find(orgId: Uuid, id: String): Launch? = database.inOrg(orgId) {
        launchRead(orgId, id).singleOrNull()
    }

    override suspend fun setCaps(orgId: Uuid, id: String, caps: LaunchCaps): Boolean = database.inOrg(orgId) {
        val l = LaunchesLaunches
        l.update({ (l.orgId eq orgId) and (l.id eq id) }) {
            it[budgetMicros] = caps.budgetMicros
            it[maxConcurrent] = caps.maxConcurrent
            it[paused] = caps.paused
        } > 0
    }

    override suspend fun labelsOf(orgId: Uuid, id: String): Map<String, String> = find(orgId, id)?.labels.orEmpty()

    override suspend fun ofCloudSession(orgId: Uuid, cloudSessionId: Uuid, limit: Int): List<String> =
        database.inOrg(orgId) { ofCloudSessionRead(orgId, cloudSessionId, limit).map { it[LaunchesLaunches.id] } }
}

private val L = LaunchesLaunches
private val json = Json
private val labelsSerializer = MapSerializer(String.serializer(), String.serializer())

/**
 * The key of the advisory lock that serializes one organization's launches: `'launches:' || org_id`, the key the
 * former (sqlx4k) version hashed, so that both versions take turns while they serve together.
 */
internal fun launchesKey(orgId: Uuid): String = "launches:$orgId"

/** The launches counted in a window, and the oldest of them (the window's start when none). */
internal class LaunchWindow(val count: Int, val oldest: Instant)

private fun window(condition: Op<Boolean>, since: Instant): Read<LaunchWindow> {
    val count = L.createdAt.count()
    val oldest = L.createdAt.min()
    return Read(L.select(count, oldest).where { condition and (L.createdAt greater since) }) { row ->
        val n = row[count].toInt()
        LaunchWindow(n, row[oldest] ?: since)
    }
}

/** The launches of one person in one organization since an instant: the index on its creations. */
internal fun personWindow(orgId: Uuid, userId: Uuid, since: Instant): Read<LaunchWindow> =
    window((L.orgId eq orgId) and (L.userId eq userId), since)

/** The launches of one organization since an instant. */
internal fun orgWindow(orgId: Uuid, since: Instant): Read<LaunchWindow> = window(L.orgId eq orgId, since)

/** A launch by its primary key. */
internal fun launchRead(orgId: Uuid, id: String): Read<Launch> =
    Read(L.select(L.columns).where { (L.orgId eq orgId) and (L.id eq id) }) { it.toLaunch() }

/** The launches of a cloud session, newest first: the index on the cloud session. */
internal fun ofCloudSessionRead(orgId: Uuid, cloudSessionId: Uuid, limit: Int): Query = L.select(L.id)
    .where { (L.orgId eq orgId) and (L.cloudSessionId eq cloudSessionId) }
    .orderBy(L.createdAt to SortOrder.DESC)
    .limit(limit)

/** The purge function's call: `SELECT launches_purge(CAST(? AS TIMESTAMPTZ))`, the launches it deleted. */
internal fun purgeRead(before: Instant): Read<Long> {
    val purged = CustomFunction(
        "launches_purge",
        LongColumnType(),
        Cast(QueryParameter(before, InstantColumnType()), InstantColumnType()),
    )
    return Read(Table.Dual.select(purged)) { it[purged] }
}

private fun ResultRow.toLaunch() = Launch(
    id = this[L.id],
    orgId = this[L.orgId],
    userId = this[L.userId],
    dev = this[L.dev],
    repo = this[L.repo],
    agent = this[L.agent],
    model = this[L.model],
    createdAt = this[L.createdAt],
    expiresAt = this[L.expiresAt],
    cloudSessionId = this[L.cloudSessionId],
    workflowId = this[L.workflowId],
    labels = json.decodeFromString(labelsSerializer, this[L.labels]),
    caps = LaunchCaps(this[L.budgetMicros], this[L.maxConcurrent], this[L.paused]),
)
