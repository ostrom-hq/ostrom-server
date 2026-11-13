// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain

import run.ostrom.server.artifacts.domain.storage.ArtifactContents
import run.ostrom.server.kernel.errors.Pages
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Publishes artifacts and their versions, while artifacts are active (otherwise
 * [run.ostrom.server.artifacts.domain.storage.ArtifactsInactiveException], with TeamCode's reason).
 */
interface PublishArtifactUseCase {
    /**
     * Publishes a new artifact, owned by the caller, at version 1.
     *
     * @param caller The member.
     * @param title Its title.
     * @param access `personal`, `team` or `link` (a person only).
     * @param files Its files.
     *
     * @return The artifact.
     *
     * @throws ArtifactPersonRequiredException For `link` when no person asks.
     */
    suspend fun create(caller: ArtifactCaller, title: String, access: String, files: List<UploadedFile>): Artifact

    /**
     * Publishes a new version of one of the caller's artifacts.
     *
     * @return The version.
     *
     * @throws ArtifactConflictException When another version was published at the same time.
     */
    suspend fun addVersion(caller: ArtifactCaller, id: Uuid, files: List<UploadedFile>): ArtifactVersion
}

/**
 * [PublishArtifactUseCase]: the files are checked (paths, sizes, secrets) before anything is stored, and each
 * distinct content is stored once per artifact. The audit log is told of each artifact created, each version published,
 * and each publication refused (a file that holds a secret, or a public one asked by a program).
 */
internal class PublishArtifactUseCaseImpl(
    private val repository: ArtifactsRepository,
    private val contents: ArtifactContents,
    private val digest: ContentDigest,
    private val clock: Clock,
    private val events: ArtifactEvents,
) : PublishArtifactUseCase {
    private val rules = ArtifactAccessRules(repository)

    override suspend fun create(
        caller: ArtifactCaller,
        title: String,
        access: String,
        files: List<UploadedFile>,
    ): Artifact {
        contents.requireActive(caller.orgId)
        val checkedTitle = ArtifactRules.title(title)
        val checkedAccess = parseAccess(access)
        if (checkedAccess == ArtifactAccess.LINK && !caller.person) {
            events.record(caller, PUBLISH_REFUSED, null, mapOf("access" to access, "reason" to "person_required"))
            throw ArtifactPersonRequiredException()
        }
        checked(caller, null, files)
        val now = clock.now()
        val id = Uuid.random()
        val (recorded, blobs) = record(files)
        val artifact = Artifact(
            id = id,
            orgId = caller.orgId,
            ownerId = caller.memberId,
            title = checkedTitle,
            access = checkedAccess,
            latestVersion = 1,
            linkSlug = linkSlugFor(checkedAccess),
            createdAt = now,
            updatedAt = now,
        )
        val version = ArtifactVersion(id, 1, ArtifactRules.entry(files.map { it.path }), recorded, now, caller.memberId)
        contents.write(caller.orgId, id, blobs)
        repository.create(artifact, version)
        events.record(caller, "created", id, mapOf("title" to checkedTitle, "access" to checkedAccess.wire))
        return artifact
    }

    override suspend fun addVersion(caller: ArtifactCaller, id: Uuid, files: List<UploadedFile>): ArtifactVersion {
        contents.requireActive(caller.orgId)
        val artifact = rules.owned(caller, id)
        checked(caller, id, files)
        val (recorded, blobs) = record(files)
        val known = repository.blobDigests(artifact.orgId, id)
        val now = clock.now()
        val version = ArtifactVersion(
            artifactId = id,
            number = artifact.latestVersion + 1,
            entry = ArtifactRules.entry(files.map { it.path }),
            files = recorded,
            createdAt = now,
            createdBy = caller.memberId,
        )
        // The contents go to the bucket first: a version is never recorded without them (a lost race leaves objects
        // the artifact's folder already holds, or that the collection removes).
        contents.write(caller.orgId, id, blobs - known)
        repository.addVersion(caller.orgId, version, now) ?: throw ArtifactConflictException()
        events.record(caller, "version_published", id, mapOf("version" to version.number.toString()))
        return version
    }

    private suspend fun checked(caller: ArtifactCaller, id: Uuid?, files: List<UploadedFile>) {
        try {
            ArtifactRules.check(files)
        } catch (refused: ArtifactSecretException) {
            events.record(caller, PUBLISH_REFUSED, id, mapOf("reason" to "secret"))
            throw refused
        }
    }

    private suspend fun record(files: List<UploadedFile>): Pair<List<ArtifactFile>, Map<String, ByteArray>> {
        val blobs = mutableMapOf<String, ByteArray>()
        val recorded = files.sortedBy { it.path }.map { file ->
            val sha = digest.sha256(file.bytes)
            blobs[sha] = file.bytes
            ArtifactFile(file.path, sha, file.bytes.size.toLong(), ArtifactRules.contentType(file.path))
        }
        return recorded to blobs
    }
}

/**
 * Reads artifacts, their versions and files, while artifacts are active (otherwise
 * [run.ostrom.server.artifacts.domain.storage.ArtifactsInactiveException], with TeamCode's reason).
 */
interface ReadArtifactsUseCase {
    /**
     * Lists what the caller reads, most recently changed first.
     *
     * @param before The last artifact of the previous page.
     * @param limit The page size, 1 to 100 (20 by default).
     *
     * @return The page.
     */
    suspend fun list(caller: ArtifactCaller, before: Uuid?, limit: Int?): List<Artifact>

    /**
     * Reads an artifact.
     *
     * @throws ArtifactNotFoundException When it is unknown or unreadable.
     */
    suspend fun get(caller: ArtifactCaller, id: Uuid): Artifact

    /**
     * Lists an artifact's versions, newest first.
     *
     * @throws ArtifactNotFoundException When it is unknown or unreadable.
     */
    suspend fun versions(caller: ArtifactCaller, id: Uuid): List<ArtifactVersion>

    /**
     * Reads a file of a version.
     *
     * @param number The version; the latest when null.
     * @param path The file; the entry page when null.
     *
     * @return The file.
     *
     * @throws ArtifactNotFoundException When the artifact, the version or the file is unknown or unreadable.
     */
    suspend fun file(caller: ArtifactCaller, id: Uuid, number: Int?, path: String?): ServedFile
}

/** [ReadArtifactsUseCase]. */
internal class ReadArtifactsUseCaseImpl(
    private val repository: ArtifactsRepository,
    private val contents: ArtifactContents,
) : ReadArtifactsUseCase {
    private val rules = ArtifactAccessRules(repository)

    override suspend fun list(caller: ArtifactCaller, before: Uuid?, limit: Int?): List<Artifact> {
        contents.requireActive(caller.orgId)
        val size = limit ?: DEFAULT_PAGE
        ensureValid(size in 1..MAX_PAGE) { "limit must be between 1 and $MAX_PAGE." }
        // A `before` that names no artifact of the organization (forged, or another's) is a 400, not an empty page.
        return Pages.checked("before", before, repository.listReadable(caller, before, size)) {
            repository.get(caller.orgId, it) != null
        }
    }

    override suspend fun get(caller: ArtifactCaller, id: Uuid): Artifact {
        contents.requireActive(caller.orgId)
        return rules.readable(caller, id)
    }

    override suspend fun versions(caller: ArtifactCaller, id: Uuid): List<ArtifactVersion> {
        contents.requireActive(caller.orgId)
        rules.readable(caller, id)
        return repository.versions(caller.orgId, id)
    }

    override suspend fun file(caller: ArtifactCaller, id: Uuid, number: Int?, path: String?): ServedFile {
        contents.requireActive(caller.orgId)
        val artifact = rules.readable(caller, id)
        return serveFile(repository, contents, artifact, number ?: artifact.latestVersion, path)
            ?: throw ArtifactNotFoundException()
    }

    private companion object {
        const val DEFAULT_PAGE = 20
        const val MAX_PAGE = 100
    }
}

/**
 * Reads a file of a version: its row here, its content from the organization's bucket.
 *
 * @param path The file; the entry page when null or empty.
 *
 * @return The file, or null when the version or the file does not exist.
 *
 * @throws run.ostrom.server.artifacts.domain.storage.ArtifactStorageUnavailableException When the bucket fails.
 */
internal suspend fun serveFile(
    repository: ArtifactsRepository,
    contents: ArtifactContents,
    artifact: Artifact,
    number: Int,
    path: String?,
): ServedFile? {
    val version = repository.version(artifact.orgId, artifact.id, number)
    val wanted = path?.takeIf { it.isNotEmpty() } ?: version?.entry
    val file = version?.files?.firstOrNull { it.path == wanted } ?: return null
    return ServedFile(contents.read(artifact.orgId, artifact.id, file), file.contentType)
}

/**
 * Parses an access level.
 *
 * @throws InvalidArtifactRequestException For another value.
 */
internal fun parseAccess(access: String): ArtifactAccess =
    ArtifactAccess.fromWire(access) ?: throw InvalidArtifactRequestException("access is personal, team or link.")

/** A new public link's secret for [ArtifactAccess.LINK], null otherwise. */
internal fun linkSlugFor(access: ArtifactAccess): String? =
    if (access == ArtifactAccess.LINK) Uuid.random().toHexString() + Uuid.random().toHexString() else null

/** The event of a publication refused. */
private const val PUBLISH_REFUSED = "publish_refused"
