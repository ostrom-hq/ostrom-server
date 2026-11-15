// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.runners.domain.CloudConversationException
import run.ostrom.server.runners.domain.CloudConversationRules
import run.ostrom.server.runners.domain.CloudFile
import run.ostrom.server.runners.domain.CloudFileBegin
import run.ostrom.server.runners.domain.CloudFileRules
import run.ostrom.server.runners.domain.CloudFileStore
import run.ostrom.server.runners.domain.CloudInputStatus
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.DesiredState
import run.ostrom.server.runners.domain.SessionState
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [CloudFileStore] on PostgreSQL through Exposed (TeamCode's `SqlCloudFileStore`, the tables of [RunnersMigrations]'
 * `session_files`). Every write that counts a session's files locks the session row first (as inputs do), and the
 * author's daily counter is locked after it. What crosses organizations (the expiry, the files to delete, the past
 * days' counters) goes through the `ostrom_router` functions of that version.
 *
 * @param database The JDBC pool.
 * @param clock The time the rows are written at.
 */
@Suppress("TooManyFunctions") // TeamCode's store, one function per operation and its two quota checks.
internal class CloudFileStoreImpl(
    private val database: JdbcDatabase,
    private val clock: Clock,
) : CloudFileStore {
    override suspend fun begin(file: CloudFile, expectedOwnerId: String, announcedBytes: Long): CloudFileBegin =
        database.inOrg(file.orgId) {
            val s = RunnersSessionsTable
            val session = s.select(s.ownerId, s.state, s.desiredState)
                .where { (s.orgId eq file.orgId) and (s.id eq file.sessionId) }.forUpdate().singleOrNull()
                ?: throw CloudSessionNotFoundException()
            if (session[s.ownerId] != expectedOwnerId) {
                throw CloudConversationRules.conflict("session_busy", "The cloud session changed owner. Retry.")
            }
            val ended = SessionState.fromWire(session[s.state]).isFinal ||
                session[s.desiredState] == DesiredState.TERMINATED.wire
            if (ended) {
                throw CloudConversationRules.conflict(
                    "session_not_running",
                    "The cloud session is terminated or being deleted.",
                )
            }
            val existing = file.clientKey?.let { key -> existingOf(file, key) }
            if (existing != null) return@inOrg CloudFileBegin.Existing(existing)
            val now = clock.now()
            checkSession(file.orgId, file.sessionId, null, announcedBytes, unsent = true)
            checkDay(file.orgId, file.authorId, now, announcedBytes, lock = false)
            val f = RunnersSessionFiles
            f.insert {
                it[id] = file.id
                it[orgId] = file.orgId
                it[sessionId] = file.sessionId
                it[authorId] = file.authorId
                it[credentialId] = file.credentialId
                it[name] = file.name
                it[size] = announcedBytes
                it[clientKey] = file.clientKey
                it[createdAt] = file.createdAt
                it[expiresAt] = file.expiresAt
            }
            CloudFileBegin.Started
        }

    /** The file of this author, session and key; a failed upload frees its key for the retry (the sweep removes it). */
    private fun existingOf(file: CloudFile, key: String): CloudFile? {
        val f = RunnersSessionFiles
        val row = f.select(f.columns).where {
            (f.orgId eq file.orgId) and (f.sessionId eq file.sessionId) and (f.authorId eq file.authorId) and
                (f.clientKey eq key)
        }.singleOrNull()?.let(::readFile)
        val failed = row != null && row.completedAt == null && row.deletingSince != null
        if (failed) f.update({ (f.orgId eq file.orgId) and (f.id eq row.id) }) { it[clientKey] = null }
        return row.takeUnless { failed }
    }

    override suspend fun complete(
        orgId: Uuid,
        id: Uuid,
        name: String,
        contentType: String,
        size: Long,
        sha256: String,
        requestDigest: String?,
    ): CloudFile = database.inOrg(orgId) {
        val f = RunnersSessionFiles
        val owning = f.select(f.sessionId).where { (f.orgId eq orgId) and (f.id eq id) }.singleOrNull() ?: throw gone()
        val sessionId = owning[f.sessionId]
        val s = RunnersSessionsTable
        s.select(s.id).where { (s.orgId eq orgId) and (s.id eq sessionId) }.forUpdate().singleOrNull()
            ?: throw gone()
        val row = f.select(f.columns).where { (f.orgId eq orgId) and (f.id eq id) }.forUpdate().single()
            .let(::readFile)
        if (row.deletingSince != null || row.completedAt != null) throw gone()
        val now = clock.now()
        checkSession(orgId, sessionId, id, size, unsent = false)
        checkDay(orgId, row.authorId, now, size, lock = true)
        val u = RunnersFileUsage
        u.update({ (u.orgId eq orgId) and (u.memberId eq row.authorId) and (u.day eq dayOf(now)) }) {
            it[bytes] = u.bytes + size
            it[files] = u.files + 1
        }
        val expires = row.createdAt + CloudFileRules.TTL_MILLIS.milliseconds
        f.update({ (f.orgId eq orgId) and (f.id eq id) }) {
            it[f.name] = name
            it[f.contentType] = contentType
            it[f.size] = size
            it[f.sha256] = sha256
            it[f.requestDigest] = requestDigest
            it[completedAt] = now
            it[expiresAt] = expires
        }
        row.copy(
            name = name, contentType = contentType, size = size, sha256 = sha256, requestDigest = requestDigest,
            completedAt = now, expiresAt = expires,
        )
    }

    override suspend fun abandon(orgId: Uuid, id: Uuid) {
        database.inOrg(orgId) {
            val f = RunnersSessionFiles
            val now = clock.now()
            f.update({ (f.orgId eq orgId) and (f.id eq id) and f.deletingSince.isNull() }) { it[deletingSince] = now }
        }
    }

    override suspend fun deliverable(orgId: Uuid, sessionId: Uuid, epoch: Long, id: Uuid): CloudFile? =
        database.inOrg(orgId) {
            val f = RunnersSessionFiles
            val i = RunnersSessionInputs
            f.join(i, JoinType.INNER, f.inputId, i.id).select(f.columns).where {
                (f.orgId eq orgId) and (f.id eq id) and (f.sessionId eq sessionId) and f.completedAt.isNotNull() and
                    f.deletingSince.isNull() and (i.sessionId eq sessionId) and
                    (i.status eq CloudInputStatus.DELIVERED.wire) and (i.deliveredEpoch eq epoch)
            }.singleOrNull()?.let(::readFile)
        }

    override suspend fun expire(limit: Int): Int = database.query { scalar(expireFiles(clock.now(), limit)).toInt() }

    override suspend fun deleting(limit: Int): List<CloudFile> {
        val due = database.query {
            val d = RunnersFilesDeleting
            d.of(limit).select(d.orgId, d.id).map { it[d.orgId] to it[d.id] }
        }
        return due.groupBy({ it.first }, { it.second }).flatMap { (orgId, ids) ->
            database.inOrg(orgId) {
                val f = RunnersSessionFiles
                f.select(f.columns).where { (f.orgId eq orgId) and (f.id inList ids) }.map(::readFile)
            }
        }
    }

    override suspend fun forget(orgId: Uuid, ids: List<Uuid>) {
        if (ids.isEmpty()) return
        database.inOrg(orgId) {
            val f = RunnersSessionFiles
            f.deleteWhere { (f.orgId eq orgId) and (f.id inList ids) and f.deletingSince.isNotNull() }
        }
    }

    override suspend fun purgeUsage(day: Long): Int = database.query { scalar(purgeFileUsage(day)).toInt() }

    /**
     * The session's own bounds: files no message names yet (when a new one is [unsent]) and bytes its host has not
     * received, [bytes] more included ([except] is the file being completed, counted with its real size).
     */
    private fun checkSession(orgId: Uuid, sessionId: Uuid, except: Uuid?, bytes: Long, unsent: Boolean) {
        val f = RunnersSessionFiles
        val live = (f.orgId eq orgId) and (f.sessionId eq sessionId) and f.deletingSince.isNull() and
            (if (except != null) f.id neq except else Op.TRUE)
        if (unsent) {
            val waiting = f.select(f.id).where { live and f.inputId.isNull() }
                .limit(CloudFileRules.MAX_UNSENT_FILES_PER_SESSION).count()
            if (waiting >= CloudFileRules.MAX_UNSENT_FILES_PER_SESSION) {
                throw CloudFileRules.quota(
                    "This session already holds ${CloudFileRules.MAX_UNSENT_FILES_PER_SESSION} files no message " +
                        "names yet. Send them, or wait for them to expire.",
                )
            }
        }
        val total = f.size.sum()
        val held = f.select(total).where { live }.single()[total] ?: 0L
        if (held + bytes > CloudFileRules.SESSION_BUFFER_BYTES) {
            throw CloudFileRules.quota(
                "This session already holds ${held / CloudFileRules.MEBIBYTE} MiB of files its host has not " +
                    "received; it holds ${CloudFileRules.SESSION_BUFFER_BYTES / CloudFileRules.MEBIBYTE} MiB at most.",
            )
        }
    }

    /** The author's UTC day: at most 500 files and 1 GiB; [lock] creates and locks its counter. */
    private fun checkDay(orgId: Uuid, memberId: String, now: Instant, bytes: Long, lock: Boolean) {
        val day = dayOf(now)
        val u = RunnersFileUsage
        if (lock) {
            u.insertIgnore {
                it[u.orgId] = orgId
                it[u.memberId] = memberId
                it[u.day] = day
                it[u.bytes] = 0
                it[files] = 0
            }
        }
        val usage = u.select(u.bytes, u.files)
            .where { (u.orgId eq orgId) and (u.memberId eq memberId) and (u.day eq day) }
            .let { if (lock) it.forUpdate() else it }.singleOrNull()
        val usedBytes = usage?.get(u.bytes) ?: 0L
        val usedFiles = usage?.get(u.files) ?: 0
        if (usedFiles >= CloudFileRules.MEMBER_DAILY_FILES || usedBytes + bytes > CloudFileRules.MEMBER_DAILY_BYTES) {
            val retry = ((day + 1) * CloudFileRules.DAY_MILLIS - now.toEpochMilliseconds() + MILLIS - 1) / MILLIS
            throw CloudFileRules.quota(
                "You have sent your ${CloudFileRules.MEMBER_DAILY_FILES} files or " +
                    "${CloudFileRules.MEMBER_DAILY_BYTES / CloudFileRules.MEBIBYTE} MiB for today. Try again " +
                    "tomorrow (UTC).",
                retry,
            )
        }
    }

    private companion object {
        const val MILLIS = 1_000L

        fun dayOf(now: Instant): Long = now.toEpochMilliseconds() / CloudFileRules.DAY_MILLIS

        fun gone() = CloudConversationException(
            ErrorKind.GONE,
            "file_expired",
            "The upload expired before it completed. Send the file again.",
        )
    }
}
