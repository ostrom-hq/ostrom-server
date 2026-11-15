// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.ArtifactAccess
import run.ostrom.server.artifacts.domain.ArtifactCaller
import run.ostrom.server.artifacts.domain.ArtifactFile
import run.ostrom.server.artifacts.domain.ArtifactFilter
import run.ostrom.server.artifacts.domain.ArtifactOrigin
import run.ostrom.server.artifacts.domain.ArtifactOriginKind
import run.ostrom.server.artifacts.domain.ArtifactScope
import run.ostrom.server.artifacts.domain.ArtifactVersion
import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.rowBefore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.compoundAnd
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.updateReturning
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [ArtifactsRepository] on PostgreSQL (tables of [ArtifactsMigrations]). A version's file list is JSON in
 * `artifacts_versions.files`; each distinct content is one `artifacts_blobs` row per artifact (its size; the bytes are
 * in the organization's bucket, [run.ostrom.server.artifacts.domain.storage.ArtifactContents]), so republishing an
 * unchanged file stores nothing. A row that still holds `bytes` predates the bucket and waits to be moved there.
 *
 * Every read and write runs confined to the artifact's organization (`inOrg`, S2-01). Only `artifacts_routes` is
 * read across organizations, only through its `SECURITY DEFINER` functions ([ArtifactsRouteById],
 * [ArtifactsRouteBySlug]): the content origin finds an artifact's organization there by its id or its link slug, then
 * reads the artifact in its organization.
 *
 * @param database The serving role's pool.
 */
@Suppress("TooManyFunctions") // One implementation of the module's one port.
internal class ArtifactsRepositoryImpl(private val database: JdbcDatabase) : ArtifactsRepository {
    override suspend fun create(artifact: Artifact, version: ArtifactVersion) {
        database.inOrg(artifact.orgId) {
            val a = ArtifactsArtifacts
            a.insert {
                it[id] = artifact.id
                it[orgId] = artifact.orgId
                it[ownerId] = artifact.ownerId
                it[title] = artifact.title
                it[access] = artifact.access.wire
                it[latestVersion] = artifact.latestVersion
                it[linkSlug] = artifact.linkSlug
                it[createdAt] = artifact.createdAt
                it[updatedAt] = artifact.updatedAt
                it[originLaunchId] = artifact.origin?.launchId
                it[originSessionId] = artifact.origin?.cloudSessionId
            }
            ArtifactsRoutes.insert {
                it[artifactId] = artifact.id
                it[orgId] = artifact.orgId
                it[linkSlug] = artifact.linkSlug
            }
            insertContent(artifact.orgId, version)
        }
    }

    override suspend fun addVersion(orgId: Uuid, version: ArtifactVersion, now: Instant): Artifact? =
        database.inOrg(orgId) {
            val a = ArtifactsArtifacts
            val updated = a.updateReturning(
                ARTIFACT_COLUMNS,
                where = {
                    (a.id eq version.artifactId) and (a.orgId eq orgId) and
                        (a.latestVersion eq version.number - 1)
                },
            ) {
                it[latestVersion] = version.number
                it[updatedAt] = now
            }.firstOrNull()?.toArtifact()
            if (updated != null) insertContent(orgId, version)
            updated
        }

    override suspend fun updateSettings(artifact: Artifact) {
        database.inOrg(artifact.orgId) {
            val a = ArtifactsArtifacts
            a.update({ (a.id eq artifact.id) and (a.orgId eq artifact.orgId) }) {
                it[title] = artifact.title
                it[access] = artifact.access.wire
                it[linkSlug] = artifact.linkSlug
                it[updatedAt] = artifact.updatedAt
            }
            val r = ArtifactsRoutes
            r.update({ (r.artifactId eq artifact.id) and (r.orgId eq artifact.orgId) }) {
                it[linkSlug] = artifact.linkSlug
            }
        }
    }

    override suspend fun get(orgId: Uuid, id: Uuid): Artifact? = database.inOrg(orgId) {
        val a = ArtifactsArtifacts
        a.select(ARTIFACT_COLUMNS).where { (a.id eq id) and (a.orgId eq orgId) }.firstOrNull()?.toArtifact()
    }

    override suspend fun getById(id: Uuid): Artifact? {
        val route = ArtifactsRouteById
        val row = database.query {
            route.of(id).select(route.artifactId, route.orgId).firstOrNull()
        } ?: return null
        return get(row[route.orgId], row[route.artifactId])
    }

    override suspend fun getByLinkSlug(slug: String): Artifact? {
        val route = ArtifactsRouteBySlug
        val row = database.query {
            route.of(slug).select(route.artifactId, route.orgId).firstOrNull()
        } ?: return null
        return get(row[route.orgId], row[route.artifactId])
    }

    override suspend fun listReadable(
        caller: ArtifactCaller,
        before: Uuid?,
        limit: Int,
        filter: ArtifactFilter,
    ): List<Artifact> = database.inOrg(caller.orgId) {
        readableArtifacts(caller, before, limit, filter).map { it.toArtifact() }
    }

    override suspend fun versions(orgId: Uuid, artifactId: Uuid): List<ArtifactVersion> = database.inOrg(orgId) {
        val v = ArtifactsVersions
        v.select(VERSION_COLUMNS)
            .where { (v.artifactId eq artifactId) and (v.orgId eq orgId) }
            .orderBy(v.number to SortOrder.DESC)
            .map { it.toVersion() }
    }

    override suspend fun version(orgId: Uuid, artifactId: Uuid, number: Int): ArtifactVersion? =
        database.inOrg(orgId) {
            val v = ArtifactsVersions
            v.select(VERSION_COLUMNS)
                .where { (v.artifactId eq artifactId) and (v.orgId eq orgId) and (v.number eq number) }
                .firstOrNull()?.toVersion()
        }

    override suspend fun blobDigests(orgId: Uuid, artifactId: Uuid): Set<String> = database.inOrg(orgId) {
        val b = ArtifactsBlobs
        b.select(b.sha256).where { (b.artifactId eq artifactId) and (b.orgId eq orgId) }.map { it[b.sha256] }.toSet()
    }

    override suspend fun blobSizes(orgId: Uuid, artifactId: Uuid): Map<String, Long> = database.inOrg(orgId) {
        val b = ArtifactsBlobs
        b.select(b.sha256, b.size).where { (b.artifactId eq artifactId) and (b.orgId eq orgId) }
            .associate { it[b.sha256] to it[b.size] }
    }

    override suspend fun members(orgId: Uuid, artifactId: Uuid): List<String> = database.inOrg(orgId) {
        val m = ArtifactsMembers
        m.select(m.memberId).where { (m.artifactId eq artifactId) and (m.orgId eq orgId) }
            .orderBy(m.memberId)
            .map { it[m.memberId] }
    }

    override suspend fun setMember(orgId: Uuid, artifactId: Uuid, memberId: String, share: Boolean) {
        database.inOrg(orgId) {
            val a = ArtifactsArtifacts
            val artifact = a.select(a.id).where { (a.id eq artifactId) and (a.orgId eq orgId) }
            val m = ArtifactsMembers
            if (share) {
                // A bare `ON CONFLICT DO NOTHING`, as before: the member's key is the only constraint.
                m.insertIgnore(
                    a.select(a.id, a.orgId, QueryParameter(memberId, TextColumnType()))
                        .where { (a.id eq artifactId) and (a.orgId eq orgId) },
                    listOf(m.artifactId, m.orgId, m.memberId),
                )
            } else {
                m.deleteWhere { (m.memberId eq memberId) and (m.artifactId inSubQuery artifact) }
            }
        }
    }

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean = database.inOrg(orgId) {
        val a = ArtifactsArtifacts
        a.deleteWhere { (a.id eq id) and (a.orgId eq orgId) } > 0
    }

    // One row per distinct content of the artifact, its size only: the bytes are in the bucket.
    private fun JdbcTransaction.insertContent(orgId: Uuid, version: ArtifactVersion) {
        val b = ArtifactsBlobs
        version.files.distinctBy { it.sha256 }.forEach { file ->
            // `ON CONFLICT (artifact_id, sha256) DO UPDATE SET marked_at = NULL`: a content used again is unmarked.
            b.upsert(b.artifactId, b.sha256, onUpdate = { it[b.markedAt] = null }) {
                it[artifactId] = version.artifactId
                it[this.orgId] = orgId
                it[sha256] = file.sha256
                it[size] = file.size
            }
        }
        val files = version.files.map { FileRow(it.path, it.sha256, it.size, it.contentType) }
        ArtifactsVersions.insert {
            it[this.orgId] = orgId
            it[artifactId] = version.artifactId
            it[number] = version.number
            it[entry] = version.entry
            it[this.files] = JSON.encodeToString(FILES, files)
            it[createdAt] = version.createdAt
            it[createdBy] = version.createdBy
            it[digest] = version.digest
            it[baseVersion] = version.baseVersion
            it[credentialId] = version.credentialId
            it[originLaunchId] = version.origin?.launchId
            it[originSessionId] = version.origin?.cloudSessionId
        }
    }

    private fun ResultRow.toArtifact(): Artifact {
        val a = ArtifactsArtifacts
        return Artifact(
            id = this[a.id],
            orgId = this[a.orgId],
            ownerId = this[a.ownerId],
            title = this[a.title],
            access = storedAccess(this[a.access]),
            latestVersion = this[a.latestVersion],
            linkSlug = this[a.linkSlug],
            createdAt = this[a.createdAt],
            updatedAt = this[a.updatedAt],
            origin = originOf(this[a.originLaunchId], this[a.originSessionId]),
        )
    }

    private fun originOf(launchId: String?, cloudSessionId: String?) =
        launchId?.let { ArtifactOrigin(it, cloudSessionId) }

    /** Reads a stored access level; anything unexpected is read as the narrowest, `personal`. */
    private fun storedAccess(wire: String): ArtifactAccess = when (wire) {
        "team" -> ArtifactAccess.TEAM
        "link" -> ArtifactAccess.LINK
        else -> ArtifactAccess.PERSONAL
    }

    private fun ResultRow.toVersion(): ArtifactVersion {
        val v = ArtifactsVersions
        return ArtifactVersion(
            artifactId = this[v.artifactId],
            number = this[v.number],
            entry = this[v.entry],
            files = JSON.decodeFromString(FILES, this[v.files])
                .map { ArtifactFile(it.path, it.sha256, it.size, it.type) },
            createdAt = this[v.createdAt],
            createdBy = this[v.createdBy],
            digest = this[v.digest],
            baseVersion = this[v.baseVersion],
            credentialId = this[v.credentialId],
            origin = originOf(this[v.originLaunchId], this[v.originSessionId]),
        )
    }

    /** A file as stored in `artifacts_versions.files`. */
    @Serializable
    private data class FileRow(val path: String, val sha256: String, val size: Long, val type: String)

    private companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val FILES = ListSerializer(FileRow.serializer())
    }
}

/**
 * The artifacts a caller reads, newest change first: their own, those shared with the team or the link, and those
 * shared with them, narrowed by [filter], after the artifact [before] (the keyset cursor, one index condition).
 */
internal fun readableArtifacts(caller: ArtifactCaller, before: Uuid?, limit: Int, filter: ArtifactFilter): Query {
    val a = ArtifactsArtifacts
    val m = ArtifactsMembers
    val shared = m.select(intLiteral(1)).where { (m.artifactId eq a.id) and (m.memberId eq caller.memberId) }
    val conditions = listOfNotNull(
        a.orgId eq caller.orgId,
        a.latestVersion greater 0,
        (a.ownerId eq caller.memberId) or (a.access neq ArtifactAccess.PERSONAL.wire) or exists(shared),
        scopeCondition(filter.scope, caller.memberId),
        originCondition(filter.origin),
        filter.access?.let { a.access eq it.wire },
        filter.cloudSession?.let { a.originSessionId eq it },
        filter.launch?.let { a.originLaunchId eq it },
        before?.let {
            rowBefore(
                listOf(a.updatedAt, a.id),
                a.select(a.updatedAt, a.id).where { (a.id eq it) and (a.orgId eq caller.orgId) },
            )
        },
    )
    return a.select(ARTIFACT_COLUMNS)
        .where { conditions.compoundAnd() }
        .orderBy(a.updatedAt to SortOrder.DESC, a.id to SortOrder.DESC)
        .limit(limit)
}

private val VERSION_COLUMNS = ArtifactsVersions.columns

private val ARTIFACT_COLUMNS = ArtifactsArtifacts.columns - ArtifactsArtifacts.expiresAt

/** The scope of a listing: all the team's, the caller's own, or what is shared with the caller. */
private fun scopeCondition(scope: ArtifactScope, memberId: String): Op<Boolean>? {
    val a = ArtifactsArtifacts
    val m = ArtifactsMembers
    return when (scope) {
        ArtifactScope.TEAM -> null
        ArtifactScope.MINE -> a.ownerId eq memberId
        ArtifactScope.SHARED -> exists(
            m.select(intLiteral(1)).where { (m.artifactId eq a.id) and (m.memberId eq memberId) },
        )
    }
}

/** The origin of a listing: an API publication, a local run (a launch without session) or a cloud session. */
private fun originCondition(origin: ArtifactOriginKind?): Op<Boolean>? {
    val a = ArtifactsArtifacts
    if (origin == null) return null
    return when (origin) {
        ArtifactOriginKind.API -> a.originLaunchId.isNull()
        ArtifactOriginKind.LOCAL_RUN -> a.originLaunchId.isNotNull() and a.originSessionId.isNull()
        ArtifactOriginKind.CLOUD_SESSION -> a.originSessionId.isNotNull()
    }
}
