// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The skill catalog's storage. Every block runs in one transaction confined to its organization (row-level
 * security); [write] also serializes the organization's catalog writes, so two replicas can never both pass a rule
 * that depends on the current state (a limit, a pin, a retired version).
 */
interface SkillsRepository {
    /**
     * Reads in one transaction.
     *
     * @param orgId The organization.
     * @param block The reads.
     *
     * @return What [block] returns.
     */
    suspend fun <T> read(orgId: Uuid, block: suspend SkillsStore.() -> T): T

    /**
     * Writes in one transaction, after every other write of the organization's catalog has committed. A failure
     * inside rolls everything back.
     *
     * @param orgId The organization.
     * @param block The reads and writes.
     *
     * @return What [block] returns.
     */
    suspend fun <T> write(orgId: Uuid, block: suspend SkillsStore.() -> T): T
}

/** The statements of one transaction of [SkillsRepository], all in its organization. */
// One store per aggregate: splitting it would only spread the same tables over several classes.
@Suppress("TooManyFunctions")
interface SkillsStore {
    /** The skill named [name], team or personal, or null. */
    suspend fun skill(name: String): Skill?

    /** The team skills and every personal skill, newest change first, at most [limit]. */
    suspend fun catalog(limit: Int): List<Skill>

    /** [ownerId]'s personal skills, newest change first, at most [limit]. */
    suspend fun personal(ownerId: String, limit: Int): List<Skill>

    /** How many team skills there are. */
    suspend fun countTeamSkills(): Long

    /** How many personal skills [ownerId] has. */
    suspend fun countPersonalSkills(ownerId: String): Long

    /** Stores a new skill. */
    suspend fun insertSkill(skill: Skill)

    /** Writes a skill's owner, pin, audience, retirement and update time. */
    suspend fun updateSkill(skill: Skill)

    /** A version of [skillId] by its label, or null. */
    suspend fun version(skillId: Uuid, label: String): SkillVersion?

    /** [skillId]'s versions, newest first, at most [limit]. */
    suspend fun versions(skillId: Uuid, limit: Int): List<SkillVersion>

    /** How many versions [skillId] has. */
    suspend fun countVersions(skillId: Uuid): Long

    /** Stores a version and its files. */
    suspend fun insertVersion(version: SkillVersion, artifact: ValidatedArtifact)

    /** Retires a version. */
    suspend fun retireVersion(versionId: Uuid, at: Instant)

    /** [memberId]'s decision row on [skillId], cleared or not, or null. */
    suspend fun assignment(skillId: Uuid, memberId: String): Assignment?

    /** Inserts or updates a decision row, by its id. */
    suspend fun saveAssignment(assignment: Assignment, updatedBy: String)

    /** [skillId]'s explicit decisions, by member id, at most [limit]. */
    suspend fun assignments(skillId: Uuid, limit: Int): List<MemberDecision>

    /**
     * Every team skill and [memberId]'s personal skills, by name, with the pinned version and the member's decision.
     */
    suspend fun candidates(memberId: String, limit: Int): List<Candidate>

    /** A version's files, sorted by path. */
    suspend fun files(versionId: Uuid): List<ArtifactFile>

    /** The ids of the skills named [names] that [memberId] may report: the team's and their own. */
    suspend fun reportable(names: Set<String>, memberId: String): Map<String, Uuid>

    /** The digests of the published versions among [labels] of [skillIds]. */
    suspend fun digests(skillIds: Collection<Uuid>, labels: Set<String>): Map<Pair<Uuid, String>, String>

    /** Inserts or updates the state of skills on one workstation. */
    suspend fun saveInstallations(workstation: Workstation, rows: List<Pair<Uuid, InstallationReport>>, at: Instant)

    /** What [memberId]'s workstations reported, newest first, at most [limit]. */
    suspend fun installations(memberId: String, limit: Int): List<Installation>
}

/**
 * A workstation reporting its installations: the CLI's token that signs it in.
 *
 * @property id The token's id.
 * @property name Its name.
 * @property memberId Its member.
 */
data class Workstation(val id: String, val name: String, val memberId: String)
