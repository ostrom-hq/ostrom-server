// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.artifacts.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Who reads an artifact besides its owner.
 *
 * @property wire The contract's value.
 */
enum class ArtifactAccess(val wire: String) {
    /** The owner and the members they shared it with: a role opens nobody's personal artifact (TeamCode #238). */
    PERSONAL("personal"),

    /** Every member of the organization. */
    TEAM("team"),

    /**
     * Anyone with its link, on the isolated content origin; members of the organization through the API. Only a
     * person turns it on ([ArtifactCaller.person]).
     */
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
 * The member a request acts for. Their role in the organization gives them nothing here: an owner or an
 * administrator reads another member's personal artifact only when it is shared with them, and changes none.
 *
 * @property orgId Their organization.
 * @property memberId Their id.
 * @property person Whether a person presents the credential (a browser session or the unlocked app), not a token a
 *   program can present: only a person changes an artifact's access, makes one public or deletes one, so an agent
 *   never does (TeamCode `docs/artifacts.md`, "An agent never makes an artifact public and never deletes one").
 * @property credentialId The credential the request presented, which a reading ticket is bound to; null when unknown.
 * @property subjectId The person behind the credential (the principal's `subjectId`), whose credential a reading
 *   ticket checks is still live; null when unknown.
 */
data class ArtifactCaller(
    val orgId: Uuid,
    val memberId: String,
    val person: Boolean,
    val credentialId: String? = null,
    val subjectId: String? = null,
)

/**
 * What a caller is to an artifact they read.
 *
 * @property wire The contract's value (`Artifact.relation`).
 */
enum class ArtifactRelation(val wire: String) {
    /** They published it: they alone change, share and delete it. */
    OWNER("owner"),

    /** A personal artifact shared with them. */
    VIEWER("viewer"),

    /** A team or linked artifact of their organization. */
    TEAM("team"),
    ;

    /** How a reader relates to an artifact. */
    companion object {
        /**
         * The relation of a caller who reads [artifact].
         *
         * @return [OWNER], [TEAM] for a `team` or `link` artifact of someone else, [VIEWER] otherwise (a personal
         *   artifact is read only by its owner and the members it is shared with).
         */
        fun of(caller: ArtifactCaller, artifact: Artifact): ArtifactRelation = when {
            artifact.ownerId == caller.memberId -> OWNER
            artifact.access == ArtifactAccess.PERSONAL -> VIEWER
            else -> TEAM
        }
    }
}

/**
 * A file served to a reader.
 *
 * @property bytes The content.
 * @property contentType Its type.
 */
class ServedFile(val bytes: ByteArray, val contentType: String)
