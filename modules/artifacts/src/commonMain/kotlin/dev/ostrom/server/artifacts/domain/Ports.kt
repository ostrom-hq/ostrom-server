package dev.ostrom.server.artifacts.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The artifacts' storage: rows, versions, members and the files' bytes, one artifact per folder of blobs. */
// One port per aggregate: splitting it would only spread the same tables over several classes.
@Suppress("TooManyFunctions")
interface ArtifactsRepository {
    /**
     * Stores a new artifact with its first version and the bytes of its files.
     *
     * @param artifact The artifact, at version 1.
     * @param version Its first version.
     * @param blobs The files' bytes by SHA-256.
     */
    suspend fun create(artifact: Artifact, version: ArtifactVersion, blobs: Map<String, ByteArray>)

    /**
     * Adds a version if the artifact's latest is still the one the publisher saw.
     *
     * @param orgId The organization.
     * @param version The new version (number = the latest seen + 1).
     * @param blobs The bytes of the files the artifact does not hold yet, by SHA-256.
     * @param now The publication time.
     *
     * @return The updated artifact, or null when another version was published first.
     */
    suspend fun addVersion(
        orgId: Uuid,
        version: ArtifactVersion,
        blobs: Map<String, ByteArray>,
        now: Instant,
    ): Artifact?

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
     * Lists the artifacts a member reads: theirs, the team's and linked ones, those shared with them, and every one
     * for an administrator; most recently changed first.
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
    suspend fun versions(artifactId: Uuid): List<ArtifactVersion>

    /**
     * Reads one version.
     *
     * @return The version, or null.
     */
    suspend fun version(artifactId: Uuid, number: Int): ArtifactVersion?

    /**
     * Reads the bytes of a file.
     *
     * @return The bytes, or null.
     */
    suspend fun blob(artifactId: Uuid, sha256: String): ByteArray?

    /**
     * The digests the artifact already holds.
     *
     * @return The SHA-256s.
     */
    suspend fun blobDigests(artifactId: Uuid): Set<String>

    /**
     * The members an artifact is shared with.
     *
     * @return Their ids.
     */
    suspend fun members(artifactId: Uuid): List<String>

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
