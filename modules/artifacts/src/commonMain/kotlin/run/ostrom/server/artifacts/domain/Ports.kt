// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The artifacts' metadata: rows, versions, members and each distinct content's size. The bytes are in the
 * organization's bucket ([run.ostrom.server.artifacts.domain.storage.ArtifactContents]), never here.
 */
// One port per aggregate: splitting it would only spread the same tables over several classes.
@Suppress("TooManyFunctions")
interface ArtifactsRepository {
    /**
     * Stores a new artifact with its first version, its files' contents already in the bucket.
     *
     * @param artifact The artifact, at version 1.
     * @param version Its first version.
     */
    suspend fun create(artifact: Artifact, version: ArtifactVersion)

    /**
     * Adds a version if the artifact's latest is still the one the publisher saw.
     *
     * @param orgId The organization.
     * @param version The new version (number = the latest seen + 1), its new contents already in the bucket.
     * @param now The publication time.
     *
     * @return The updated artifact, or null when another version was published first.
     */
    suspend fun addVersion(orgId: Uuid, version: ArtifactVersion, now: Instant): Artifact?

    /**
     * Writes an artifact's title, access and public link.
     *
     * @param artifact The artifact with its new settings.
     */
    suspend fun updateSettings(artifact: Artifact)

    /**
     * Reads an artifact of an organization.
     *
     * @return The artifact, or null.
     */
    suspend fun get(orgId: Uuid, id: Uuid): Artifact?

    /**
     * Reads an artifact by id only, for a signed content ticket.
     *
     * @return The artifact, or null.
     */
    suspend fun getById(id: Uuid): Artifact?

    /**
     * Reads the artifact a public link names.
     *
     * @return The artifact, or null.
     */
    suspend fun getByLinkSlug(slug: String): Artifact?

    /**
     * Lists the artifacts a member reads: theirs, the team's and linked ones, and those shared with them, whatever
     * their role; most recently changed first.
     *
     * @param caller The member.
     * @param before The last artifact of the previous page, or null.
     * @param limit The page size.
     *
     * @return The page.
     */
    suspend fun listReadable(caller: ArtifactCaller, before: Uuid?, limit: Int): List<Artifact>

    /**
     * Lists an artifact's versions, newest first.
     *
     * @return The versions.
     */
    suspend fun versions(orgId: Uuid, artifactId: Uuid): List<ArtifactVersion>

    /**
     * Reads one version.
     *
     * @return The version, or null.
     */
    suspend fun version(orgId: Uuid, artifactId: Uuid, number: Int): ArtifactVersion?

    /**
     * The digests the artifact already holds.
     *
     * @return The SHA-256s.
     */
    suspend fun blobDigests(orgId: Uuid, artifactId: Uuid): Set<String>

    /**
     * The contents an artifact's folder holds, with their sizes.
     *
     * @return The size of each, by SHA-256.
     */
    suspend fun blobSizes(orgId: Uuid, artifactId: Uuid): Map<String, Long>

    /**
     * The members an artifact is shared with.
     *
     * @return Their ids.
     */
    suspend fun members(orgId: Uuid, artifactId: Uuid): List<String>

    /**
     * Shares or stops sharing an artifact with a member.
     *
     * @param share True to share, false to stop.
     */
    suspend fun setMember(orgId: Uuid, artifactId: Uuid, memberId: String, share: Boolean)

    /**
     * Deletes an artifact, its versions, members and bytes.
     *
     * @return True when it existed.
     */
    suspend fun delete(orgId: Uuid, id: Uuid): Boolean
}

/** SHA-256 of a file's bytes. */
fun interface ContentDigest {
    /**
     * Hashes bytes.
     *
     * @param bytes The bytes.
     *
     * @return The SHA-256, lower-case hex.
     */
    suspend fun sha256(bytes: ByteArray): String
}

/**
 * Where artifacts are read.
 *
 * @property viewerUrl The console's artifact page prefix (`https://app.ostrom.run/artifacts`): `<viewerUrl>/<id>`.
 * @property contentOrigin The isolated origin pages are served from (`https://artifacts.ostrom.run`), or null when
 *   none is configured: content is then not served at all.
 */
data class ArtifactLocations(
    val viewerUrl: String,
    val contentOrigin: String?,
)

/**
 * The organization's audit log, told what happens to an artifact as TeamCode's `docs/audit.md` names it:
 * `artifact.created`, `artifact.version_published`, `artifact.access_changed`, `artifact.member_added`,
 * `artifact.member_removed`, `artifact.deleted` and `artifact.publish_refused`. Reading writes nothing.
 */
fun interface ArtifactEvents {
    /**
     * Records [event] about the artifact [artifactId].
     *
     * @param caller Who did it.
     * @param event The event's name, without its `artifact.` prefix.
     * @param artifactId The artifact, or null when none was created.
     * @param details The facts of the line.
     */
    suspend fun record(caller: ArtifactCaller, event: String, artifactId: Uuid?, details: Map<String, String?>)
}
