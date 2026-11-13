// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.skills.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A skill of an organization's catalog (TeamCode's skill catalog, docs/skills.md). It reaches members only through its
 * [pinnedVersionId]; with [allMembers], every member without an explicit decision receives it, members who join later
 * included; an explicit *no* still excludes a member.
 *
 * @property id Its id.
 * @property orgId Its organization.
 * @property name Its name, unique in the organization (team and personal skills share one name space).
 * @property ownerId The member a personal skill belongs to, who alone receives it; null for a team skill.
 * @property pinnedVersionId The approved version, delivered; null when nothing is delivered.
 * @property pinnedVersion That version's label, read with it.
 * @property allMembers Whether members without a decision receive it.
 * @property retiredAt When it was removed from the catalog: then delivered to nobody and closed to publication.
 * @property createdAt Its first publication.
 * @property updatedAt Its last change.
 */
data class Skill(
    val id: Uuid,
    val orgId: Uuid,
    val name: String,
    val ownerId: String?,
    val pinnedVersionId: Uuid?,
    val pinnedVersion: String?,
    val allMembers: Boolean,
    val retiredAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /** Whether it was removed from the catalog. */
    val retired: Boolean get() = retiredAt != null
}

/**
 * An immutable published version: its files and digest never change.
 *
 * @property id Its id.
 * @property skillId Its skill.
 * @property version Its label, case-sensitive.
 * @property digest Its canonical digest ([ArtifactTree.digest]).
 * @property sizeBytes Its content, in bytes.
 * @property fileCount Its files.
 * @property publishedBy The member who published it (a service key's creator).
 * @property publishedAt When.
 * @property retiredAt When it was retired: it can never be pinned again.
 */
data class SkillVersion(
    val id: Uuid,
    val skillId: Uuid,
    val version: String,
    val digest: String,
    val sizeBytes: Long,
    val fileCount: Int,
    val publishedBy: String,
    val publishedAt: Instant,
    val retiredAt: Instant?,
) {
    /** Whether it can no longer be pinned. */
    val retired: Boolean get() = retiredAt != null
}

/**
 * A file's mode. The upload format has no link type: a publisher that declares `symlink` is refused.
 *
 * @property wire The contract's value.
 */
enum class FileMode(val wire: String) {
    /** An ordinary file. */
    FILE("file"),

    /** A file the agent may run. */
    EXECUTABLE("executable"),
}

/**
 * One file of an artifact.
 *
 * @property path Its validated relative path.
 * @property mode Its mode.
 * @property content Its bytes.
 */
class ArtifactFile(val path: String, val mode: FileMode, val content: ByteArray) {
    // Never print the content: a log line keeps the metadata only.
    override fun toString(): String = "ArtifactFile(path=$path, mode=$mode, size=${content.size})"
}

/**
 * A file's metadata in a validated artifact: what the canonical digest is made of.
 *
 * @property path Its path.
 * @property mode Its mode.
 * @property size Its size in bytes.
 * @property sha256 Lower-case hex of its content.
 */
data class FileEntry(val path: String, val mode: FileMode, val size: Int, val sha256: String)

/**
 * A validated artifact: its files sorted by path, their entries and their canonical digest.
 *
 * @property digest `sha256:<hex>`.
 * @property files The files, sorted by path.
 * @property entries Their metadata, in the same order.
 */
class ValidatedArtifact(val digest: String, val files: List<ArtifactFile>, val entries: List<FileEntry>) {
    /** The bytes of its content. */
    val sizeBytes: Long get() = entries.sumOf { it.size.toLong() }

    override fun toString(): String = "ValidatedArtifact(digest=$digest, files=${entries.size})"
}

/**
 * A member's explicit decision on a skill. Absent means "not assigned": it delivers nothing unless the skill is for all
 * members.
 *
 * @property wire The contract's value.
 */
enum class Decision(val wire: String) {
    /** Yes: the member receives the pinned version. */
    ALLOW("allow"),

    /** No: the member never receives it, whatever the audience. */
    DENY("deny"),
    ;

    /** Parsing the contract's values. */
    companion object {
        /**
         * Reads a wire value.
         *
         * @param wire `allow` or `deny`.
         *
         * @return The decision, or null for another value.
         */
        fun fromWire(wire: String): Decision? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A member's decision on one skill. The row stays when the decision is cleared, so its id names the same pair in the
 * audit log.
 *
 * @property id Its id, the subject of its audit events.
 * @property skillId The skill.
 * @property memberId The member.
 * @property decision The decision; null once cleared.
 * @property updatedAt Its last change.
 */
data class Assignment(
    val id: Uuid,
    val skillId: Uuid,
    val memberId: String,
    val decision: Decision?,
    val updatedAt: Instant,
)

/**
 * A member's explicit decision, as the catalog lists it.
 *
 * @property memberId The member.
 * @property decision Yes or no.
 * @property updatedAt When it was set.
 */
data class MemberDecision(val memberId: String, val decision: Decision, val updatedAt: Instant)

/**
 * A pinned skill version a member receives.
 *
 * @property name The skill.
 * @property versionId The version's id.
 * @property version Its label.
 * @property digest Its digest.
 * @property sizeBytes Its content, in bytes.
 * @property fileCount Its files.
 */
data class EffectiveSkill(
    val name: String,
    val versionId: Uuid,
    val version: String,
    val digest: String,
    val sizeBytes: Long,
    val fileCount: Int,
)

/**
 * What decides whether one skill reaches one member.
 *
 * @property skill The skill.
 * @property pinned Its pinned version, if any.
 * @property decision The member's explicit decision, if any.
 */
data class Candidate(val skill: Skill, val pinned: SkillVersion?, val decision: Decision?)

/**
 * What a publication did.
 *
 * @property skill The skill after it.
 * @property version The version.
 * @property created False when the label was already published with the same digest.
 */
data class Publication(val skill: Skill, val version: SkillVersion, val created: Boolean)

/**
 * A skill's state on a workstation.
 *
 * @property wire The contract's value.
 */
enum class InstallationStatus(val wire: String) {
    /** Installed. */
    INSTALLED("installed"),

    /** The installation failed. */
    FAILED("failed"),

    /** A skill of the member's own holds the name. */
    CONFLICT("conflict"),

    /** Removed, no longer delivered. */
    REMOVED("removed"),
    ;

    /** Parsing the contract's values. */
    companion object {
        /**
         * Reads a wire value.
         *
         * @param wire One of the four values.
         *
         * @return The status, or null for another value.
         */
        fun fromWire(wire: String): InstallationStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * What a workstation reports of one skill. It never grants nor revokes access.
 *
 * @property name The skill.
 * @property version The version, if any.
 * @property digest Its digest, if known.
 * @property status The state.
 */
data class InstallationReport(
    val name: String,
    val version: String?,
    val digest: String?,
    val status: InstallationStatus,
)

/**
 * A skill's state on one workstation, as it last reported it.
 *
 * @property deviceId The workstation (its CLI token).
 * @property deviceName Its name.
 * @property name The skill.
 * @property version The version.
 * @property digest Its digest.
 * @property status The state.
 * @property reportedAt When.
 */
data class Installation(
    val deviceId: String,
    val deviceName: String,
    val name: String,
    val version: String?,
    val digest: String?,
    val status: InstallationStatus,
    val reportedAt: Instant,
)
