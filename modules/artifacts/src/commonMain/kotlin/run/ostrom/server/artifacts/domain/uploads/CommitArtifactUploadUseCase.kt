// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain.uploads

import run.ostrom.server.artifacts.domain.Artifact
import run.ostrom.server.artifacts.domain.ArtifactAccess
import run.ostrom.server.artifacts.domain.ArtifactAccessRules
import run.ostrom.server.artifacts.domain.ArtifactCaller
import run.ostrom.server.artifacts.domain.ArtifactConflictException
import run.ostrom.server.artifacts.domain.ArtifactEvents
import run.ostrom.server.artifacts.domain.ArtifactFile
import run.ostrom.server.artifacts.domain.ArtifactPersonRequiredException
import run.ostrom.server.artifacts.domain.ArtifactRules
import run.ostrom.server.artifacts.domain.ArtifactVersion
import run.ostrom.server.artifacts.domain.ArtifactsRepository
import run.ostrom.server.artifacts.domain.ContentDigest
import run.ostrom.server.artifacts.domain.ensureValid
import run.ostrom.server.artifacts.domain.linkSlugFor
import run.ostrom.server.artifacts.domain.parseAccess
import run.ostrom.server.artifacts.domain.storage.ArtifactContents
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A file of the version to commit, by its content.
 *
 * @property path Its path in the version.
 * @property sha256 Its SHA-256.
 * @property size Its size.
 */
data class CommittedFile(val path: String, val sha256: String, val size: Long)

/**
 * The commit of an upload.
 *
 * @property uploadId The upload whose files it uses.
 * @property files The version's files.
 * @property entry The page a reader opens first; chosen as for an inline publication when null.
 * @property baseVersion The version the publisher started from (0 for a draft); the live one when null.
 * @property title The title, required for a draft; unchanged when null.
 * @property access `personal`, `team` or `link` for a draft (`personal` when null); an artifact's access changes
 *   through its settings only.
 */
data class UploadCommit(
    val uploadId: Uuid,
    val files: List<CommittedFile>,
    val entry: String? = null,
    val baseVersion: Int? = null,
    val title: String? = null,
    val access: String? = null,
)

/**
 * A version committed.
 *
 * @property version The version.
 * @property replayed The same commit came again within ten minutes: the version it made, nothing new.
 */
data class CommittedVersion(val version: ArtifactVersion, val replayed: Boolean)

/**
 * Commits an upload as an artifact's next version (TeamCode's `POST /artifacts/{id}/versions` with `uploadId`): every
 * file is sent by the upload or already in the artifact's folder, the publisher started from the live version, and the
 * same commit again within ten minutes answers the version it made.
 */
interface CommitArtifactUploadUseCase {
    /**
     * Commits.
     *
     * @return The version, and whether it is a replay.
     *
     * @throws run.ostrom.server.artifacts.domain.ArtifactNotFoundException When the artifact is not readable.
     * @throws run.ostrom.server.artifacts.domain.ArtifactNotWritableException When it is not the caller's.
     * @throws ArtifactUploadNotFoundException When the upload is closed, another credential's or another artifact's.
     * @throws ArtifactConflictException When the base is not the live version, or another commit came first.
     * @throws ArtifactBlobMissingException When a file is not in the artifact's folder.
     * @throws run.ostrom.server.artifacts.domain.InvalidArtifactRequestException When a path, the entry, the title or
     *   the access is not valid.
     */
    suspend fun commit(caller: ArtifactCaller, artifactId: Uuid, commit: UploadCommit): CommittedVersion
}

/** [CommitArtifactUploadUseCase]. */
internal class CommitArtifactUploadUseCaseImpl(
    private val artifacts: ArtifactsRepository,
    private val uploads: ArtifactUploadsRepository,
    private val contents: ArtifactContents,
    private val digest: ContentDigest,
    private val clock: Clock,
    private val events: ArtifactEvents,
) : CommitArtifactUploadUseCase {
    private val rules = ArtifactAccessRules(artifacts)

    override suspend fun commit(caller: ArtifactCaller, artifactId: Uuid, commit: UploadCommit): CommittedVersion {
        contents.requireActive(caller.orgId)
        val artifact = rules.owned(caller, artifactId)
        val now = clock.now()
        val files = filesOf(commit)
        val entry = entryOf(commit.entry, files)
        val manifest = digest.sha256(manifestOf(entry, files).encodeToByteArray())
        val base = commit.baseVersion ?: artifact.latestVersion
        val since = now - REPLAY_WINDOW
        val replay = artifacts.versions(caller.orgId, artifact.id).firstOrNull {
            it.digest == manifest && it.baseVersion == base && it.isRecentFrom(caller, since)
        }
        // Before the upload: the commit that made the version ended it.
        if (replay != null) return CommittedVersion(replay, replayed = true)
        val upload = uploads.get(caller.orgId, commit.uploadId)
            ?.takeIf { it.artifactId == artifact.id && it.isOpenFor(caller, now) }
            ?: throw ArtifactUploadNotFoundException()
        requireHeld(caller, artifact, files)
        val settings = settingsOf(caller, artifact, commit)
        val version = ArtifactVersion(
            artifactId = artifact.id,
            number = artifact.latestVersion + 1,
            entry = entry,
            files = files,
            createdAt = now,
            createdBy = caller.memberId,
            digest = manifest,
            baseVersion = base,
            credentialId = caller.credentialId,
        )
        if (base != artifact.latestVersion || artifacts.addVersion(caller.orgId, version, now) == null) {
            throw ArtifactConflictException()
        }
        if (settings != artifact) {
            artifacts.updateSettings(settings.copy(latestVersion = version.number, updatedAt = now))
        }
        uploads.end(caller.orgId, upload)
        val event = if (artifact.draft) "created" else "version_published"
        events.record(caller, event, artifact.id, mapOf("version" to version.number.toString()))
        return CommittedVersion(version, replayed = false)
    }

    private fun ArtifactVersion.isRecentFrom(caller: ArtifactCaller, since: Instant): Boolean =
        credentialId == caller.credentialId && createdAt > since

    private fun filesOf(commit: UploadCommit): List<ArtifactFile> {
        ensureValid(commit.files.size in 1..ArtifactRules.MAX_FILES) {
            "A version holds 1 to ${ArtifactRules.MAX_FILES} files."
        }
        commit.files.forEach { ArtifactRules.checkPath(it.path) }
        ensureValid(commit.files.map { it.path }.toSet().size == commit.files.size) { "Two files have the same path." }
        return commit.files.sortedBy { it.path }
            .map { ArtifactFile(it.path, it.sha256, it.size, ArtifactRules.contentType(it.path)) }
    }

    private fun entryOf(entry: String?, files: List<ArtifactFile>): String {
        val paths = files.map { it.path }
        if (entry == null) return ArtifactRules.entry(paths)
        ensureValid(entry in paths && PAGES.any { entry.endsWith(it) }) {
            "The entry is a page of the version: an .html, .htm or .md file."
        }
        return entry
    }

    // Every file is in the artifact's folder, the upload's included (stored there as they were assembled), at its size.
    private suspend fun requireHeld(caller: ArtifactCaller, artifact: Artifact, files: List<ArtifactFile>) {
        val held = artifacts.blobSizes(caller.orgId, artifact.id)
        files.firstOrNull { held[it.sha256] != it.size }?.let { throw ArtifactBlobMissingException(it.sha256) }
    }

    // A draft takes its title and access with its first version; an artifact keeps its access, its title may change.
    private suspend fun settingsOf(caller: ArtifactCaller, artifact: Artifact, commit: UploadCommit): Artifact {
        val title = commit.title?.let { ArtifactRules.title(it) }
        if (!artifact.draft) {
            ensureValid(commit.access == null || commit.access == artifact.access.wire) {
                "An artifact's access changes through its settings, by a person."
            }
            return artifact.copy(title = title ?: artifact.title)
        }
        ensureValid(title != null) { "A new artifact needs a title." }
        val access = parseAccess(commit.access ?: ArtifactAccess.PERSONAL.wire)
        if (access == ArtifactAccess.LINK && !caller.person) {
            val refusal = mapOf("access" to access.wire, "reason" to "person_required")
            events.record(caller, "publish_refused", artifact.id, refusal)
            throw ArtifactPersonRequiredException()
        }
        return artifact.copy(title = checkNotNull(title), access = access, linkSlug = linkSlugFor(access))
    }
}

// The canonical manifest: the entry, then each file's path, SHA-256 and size, sorted by path.
private fun manifestOf(entry: String, files: List<ArtifactFile>): String =
    "$entry\n" + files.joinToString("") { "${it.path}\t${it.sha256}\t${it.size}\n" }

private val REPLAY_WINDOW = 10.minutes
private val PAGES = listOf(".html", ".htm", ".md")
