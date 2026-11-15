// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.Read
import run.ostrom.server.kernel.database.lockUntilCommit
import run.ostrom.server.kernel.database.write
import run.ostrom.server.kernel.database.written
import run.ostrom.server.runners.domain.RegisteredRunner
import run.ostrom.server.runners.domain.RegisteredRunnerKey
import run.ostrom.server.runners.domain.RegisteredRunnersRepository
import run.ostrom.server.runners.domain.RunnerRegistrationOutcome
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.statements.DeleteStatement
import org.jetbrains.exposed.v1.core.statements.InsertStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [RegisteredRunnersRepository] on PostgreSQL (`runners_registered`, migrations 2 and 4 of [RunnersMigrations]):
 * every read and write runs confined to the runner's organization ([JdbcDatabase.inOrg], S2-01), except [keyOf]. The
 * labels are a JSON array of strings.
 *
 * @param database The JDBC pool.
 */
class RegisteredRunnersRepositoryImpl(private val database: JdbcDatabase) : RegisteredRunnersRepository {
    override suspend fun register(runner: RegisteredRunner, max: Int): RunnerRegistrationOutcome =
        database.inOrg(runner.orgId) {
            // The same per-organization lock as the cloud sessions' cap, under its own key.
            lockUntilCommit("runners-registered:${runner.orgId}")
            if (runnerCount(runner.orgId).single() >= max) return@inOrg RunnerRegistrationOutcome.LIMIT_REACHED
            // No capacity yet: a runner declares it at its heartbeats. A name taken (any case) inserts nothing.
            if (written { runnerInsert(runner) } == 0) {
                RunnerRegistrationOutcome.NAME_TAKEN
            } else {
                RunnerRegistrationOutcome.REGISTERED
            }
        }

    override suspend fun list(orgId: Uuid, limit: Int): List<RegisteredRunner> = database.inOrg(orgId) {
        runnerList(orgId, limit).map { it.toRunner() }
    }

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean = database.inOrg(orgId) {
        written { runnerDelete(orgId, id) } > 0
    }

    // The one read across organizations, through `runners_registered_key` (owned by `ostrom_router`, migration 4): the
    // serving role itself, never inside an organization's transaction.
    override suspend fun keyOf(id: Uuid): RegisteredRunnerKey? = database.query {
        val k = RunnersRegisteredKey
        RunnersRegisteredKey.of(id).select(k.orgId, k.keyHash).firstOrNull()
            ?.let { RegisteredRunnerKey(it[k.orgId], it[k.keyHash]) }
    }

    override suspend fun touch(orgId: Uuid, id: Uuid, keyHash: String, at: Instant, capacity: Int?): Boolean =
        database.inOrg(orgId) { written { runnerTouch(orgId, id, keyHash, at, capacity) } > 0 }

    private fun ResultRow.toRunner(): RegisteredRunner {
        val r = RunnersRegistered
        return RegisteredRunner(
            id = this[r.id],
            orgId = this[r.orgId],
            name = this[r.name],
            labels = Json.decodeFromString(LABELS, this[r.labels]),
            version = this[r.version],
            keyHash = this[r.keyHash],
            registeredBy = this[r.registeredBy],
            createdAt = this[r.createdAt],
            lastSeenAt = this[r.lastSeenAt],
            capacity = this[r.capacity],
        )
    }
}

private val LABELS = ListSerializer(String.serializer())

/** An organization's runners by name whatever its case, through `runners_registered_name`. */
internal fun runnerList(orgId: Uuid, limit: Int): Query {
    val r = RunnersRegistered
    return r.select(r.columns).where { r.orgId eq orgId }.orderBy(r.name.lowerCase()).limit(limit)
}

/** How many runners an organization has, through `runners_registered_name`. */
internal fun runnerCount(orgId: Uuid): Read<Long> {
    val r = RunnersRegistered
    val count = r.id.count()
    return Read(r.select(count).where { r.orgId eq orgId }) { it[count] }
}

/** A runner registered, unless its name is taken by another of the organization (whatever the case). */
internal fun StatementBuilder.runnerInsert(runner: RegisteredRunner): InsertStatement<Long> =
    RunnersRegistered.insertIgnore {
        it[id] = runner.id
        it[orgId] = runner.orgId
        it[name] = runner.name
        it[labels] = Json.encodeToString(LABELS, runner.labels)
        it[version] = runner.version
        it[keyHash] = runner.keyHash
        it[registeredBy] = runner.registeredBy
        it[createdAt] = runner.createdAt
        it[lastSeenAt] = runner.lastSeenAt
    }

/** One runner removed. */
internal fun StatementBuilder.runnerDelete(orgId: Uuid, id: Uuid): DeleteStatement = RunnersRegistered.deleteWhere {
    (RunnersRegistered.orgId eq orgId) and (RunnersRegistered.id eq id)
}

/**
 * A sign of life and the capacity it declares, written only while the runner has that key; GREATEST keeps the newest
 * (it skips NULL).
 */
internal fun StatementBuilder.runnerTouch(
    orgId: Uuid,
    id: Uuid,
    keyHash: String,
    at: Instant,
    capacity: Int?,
): UpdateStatement {
    val r = RunnersRegistered
    return r.update({ (r.orgId eq orgId) and (r.id eq id) and (r.keyHash eq keyHash) }) {
        it[lastSeenAt] = greatest(r.lastSeenAt, at)
        it[r.capacity] = capacity
    }
}
