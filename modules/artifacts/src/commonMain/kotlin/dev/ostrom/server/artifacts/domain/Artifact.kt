// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.artifacts.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Who reads an artifact besides its owner.
 *
 * @property wire The contract's value.
 */
enum class ArtifactAccess(val wire: String) {
    /** The owner, the members they invited and the organization's administrators. */
    PERSONAL("personal"),

    /** Every member of the organization. */
    TEAM("team"),

    /** Anyone with its link, on the isolated content origin; members of the organization through the API. */
    LINK("link"),
    ;

    /** Parsing the contract's values. */
    companion object {
        /**
         * Reads a wire value.
         *
         * @param wire `personal`, `team` or `link`.
         *
         * @return The access, or null for another value.
         */
        fun fromWire(wire: String): ArtifactAccess? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A published page or file set: an agent's report, plan or dashboard, for people to read.
 *
 * @property id Its id.
 * @property orgId The organization it belongs to; another organization never sees it.
 * @property ownerId The member it was published for.
 * @property title Its title.
 * @property access Who reads it.
 * @property latestVersion The newest version's number, from 1.
 * @property linkSlug The secret of its public link, set while [access] is [ArtifactAccess.LINK].
 * @property createdAt When it was first published.
 * @property updatedAt When its last version or setting changed.
 */
data class Artifact(
    val id: Uuid,
    val orgId: Uuid,
    val ownerId: String,
    val title: String,
    val access: ArtifactAccess,
    val latestVersion: Int,
    val linkSlug: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * One file of a version, as recorded (its bytes are stored once per artifact, by digest).
 *
 * @property path Its path in the version, relative (`index.html`, `img/a.png`).
 * @property sha256 The SHA-256 of its bytes, in hex.
 * @property size Its size in bytes.
 * @property contentType The type it is served with.
 */
data class ArtifactFile(
    val path: String,
    val sha256: String,
    val size: Long,
    val contentType: String,
)

/**
 * A version of an artifact; versions never change once published.
 *
 * @property artifactId The artifact.
 * @property number Its number, from 1.
 * @property entry The page a reader opens first.
 * @property files Its files, sorted by path.
 * @property createdAt When it was published.
 * @property createdBy The member who published it.
 */
data class ArtifactVersion(
    val artifactId: Uuid,
    val number: Int,
    val entry: String,
    val files: List<ArtifactFile>,
    val createdAt: Instant,
    val createdBy: String,
)

/**
 * A file sent for publication.
 *
 * @property path Its path in the version.
 * @property bytes Its content.
 */
class UploadedFile(val path: String, val bytes: ByteArray)

/**
 * The member a request acts for.
 *
 * @property orgId Their organization.
 * @property memberId Their id.
 * @property admin Whether they administer the organization (they read every artifact and delete any).
 */
data class ArtifactCaller(
    val orgId: Uuid,
    val memberId: String,
    val admin: Boolean,
)

/**
 * A file served to a reader.
 *
 * @property bytes The content.
 * @property contentType Its type.
 */
class ServedFile(val bytes: ByteArray, val contentType: String)
