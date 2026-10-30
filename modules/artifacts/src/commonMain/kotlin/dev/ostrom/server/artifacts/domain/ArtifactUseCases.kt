package dev.ostrom.server.artifacts.domain

import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Publishes artifacts and their versions. */
interface PublishArtifactUseCase {
    /**
     * Publishes a new artifact, owned by the caller, at version 1.
     *
     * @param caller The member.
     * @param title Its title.
     * @param access `personal`, `team` or `link`.
     * @param files Its files.
     *
     * @return The artifact.
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
 * distinct content is stored once per artifact.
 */
internal class PublishArtifactUseCaseImpl(
    private val repository: ArtifactsRepository,
    private val digest: ContentDigest,
    private val clock: Clock,
) : PublishArtifactUseCase {
    private val rules = ArtifactAccessRules(repository)

    override suspend fun create(
        caller: ArtifactCaller,
        title: String,
        access: String,
        files: List<UploadedFile>,
    ): Artifact {
        val checkedTitle = ArtifactRules.title(title)
        val checkedAccess = parseAccess(access)
        ArtifactRules.check(files)
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
        repository.create(artifact, version, blobs)
        return artifact
    }

    override suspend fun addVersion(caller: ArtifactCaller, id: Uuid, files: List<UploadedFile>): ArtifactVersion {
        val artifact = rules.writable(caller, id)
        ArtifactRules.check(files)
        val (recorded, blobs) = record(files)
        val known = repository.blobDigests(id)
        val now = clock.now()
        val version = ArtifactVersion(
            artifactId = id,
            number = artifact.latestVersion + 1,
            entry = ArtifactRules.entry(files.map { it.path }),
            files = recorded,
            createdAt = now,
            createdBy = caller.memberId,
        )
        repository.addVersion(caller.orgId, version, blobs - known, now) ?: throw ArtifactConflictException()
        return version
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

/** Reads artifacts, their versions and files. */
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
internal class ReadArtifactsUseCaseImpl(private val repository: ArtifactsRepository) : ReadArtifactsUseCase {
    private val rules = ArtifactAccessRules(repository)

    override suspend fun list(caller: ArtifactCaller, before: Uuid?, limit: Int?): List<Artifact> {
        val size = limit ?: DEFAULT_PAGE
        ensureValid(size in 1..MAX_PAGE) { "limit must be between 1 and $MAX_PAGE." }
        return repository.listReadable(caller, before, size)
    }

    override suspend fun get(caller: ArtifactCaller, id: Uuid): Artifact = rules.readable(caller, id)

    override suspend fun versions(caller: ArtifactCaller, id: Uuid): List<ArtifactVersion> {
        rules.readable(caller, id)
        return repository.versions(id)
    }

    override suspend fun file(caller: ArtifactCaller, id: Uuid, number: Int?, path: String?): ServedFile {
        val artifact = rules.readable(caller, id)
        return serveFile(repository, artifact.id, number ?: artifact.latestVersion, path)
            ?: throw ArtifactNotFoundException()
    }

    private companion object {
        const val DEFAULT_PAGE = 20
        const val MAX_PAGE = 100
    }
}

/**
 * Reads a file of a version from the storage.
 *
 * @param path The file; the entry page when null or empty.
 *
 * @return The file, or null when the version or the file does not exist.
 */
internal suspend fun serveFile(repository: ArtifactsRepository, id: Uuid, number: Int, path: String?): ServedFile? {
    val version = repository.version(id, number) ?: return null
    val wanted = path?.takeIf { it.isNotEmpty() } ?: version.entry
    return version.files.firstOrNull { it.path == wanted }
        ?.let { file -> repository.blob(id, file.sha256)?.let { ServedFile(it, file.contentType) } }
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
