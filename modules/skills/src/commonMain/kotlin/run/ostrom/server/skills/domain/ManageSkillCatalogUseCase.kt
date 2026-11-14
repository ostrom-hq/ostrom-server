// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.skills.domain

import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.skills.domain.SkillRules.audience
import run.ostrom.server.skills.domain.SkillRules.owned
import run.ostrom.server.skills.domain.SkillRules.pin
import run.ostrom.server.skills.domain.SkillRules.restore
import run.ostrom.server.skills.domain.SkillRules.retire
import run.ostrom.server.skills.domain.SkillRules.unpin
import run.ostrom.server.skills.domain.SkillRules.versionOf
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The kinds of change to one skill. */
enum class SkillChange {
    /** Stop delivery. */
    UNPIN,

    /** Remove from the catalog. */
    RETIRE,

    /** Reopen to publication. */
    RESTORE,
}

/**
 * A skill and its versions.
 *
 * @property skill The skill.
 * @property versions Its versions, newest first.
 */
data class SkillDetail(val skill: Skill, val versions: List<SkillVersion>)

/**
 * The catalog's administration (owners and admins in the console; pinning also by a key with `skills:publish` and
 * `skills:pin`) and the members' management of their personal skills.
 */
@Suppress("TooManyFunctions") // One operation per route of the catalog.
interface ManageSkillCatalogUseCase {
    /** The organization's skills: every team skill, then members' personal skills, at most [SkillLimits.ROWS]. */
    suspend fun catalog(caller: CatalogCaller): List<Skill>

    /** A skill's versions; a personal skill's too, as metadata. */
    suspend fun versions(caller: CatalogCaller, name: String): SkillDetail

    /** Refuses a caller that may not pin, before the body is read. */
    fun authorizePin(caller: CatalogCaller)

    /** Pins [version] (update or rollback). */
    suspend fun pin(caller: CatalogCaller, name: String, version: String): Skill

    /** Unpins, retires or restores a team skill. */
    suspend fun change(caller: CatalogCaller, name: String, change: SkillChange): Skill

    /** Retires a version: it can never be pinned again; the pinned one first needs another pin. */
    suspend fun retireVersion(caller: CatalogCaller, name: String, version: String): SkillVersion

    /** Delivers to all members, or only to members set to yes. */
    suspend fun setAudience(caller: CatalogCaller, name: String, allMembers: Boolean): Skill

    /** The explicit decisions. */
    suspend fun assignments(caller: CatalogCaller, name: String): List<MemberDecision>

    /**
     * Sets a member's decision, or clears it with null; idempotent.
     *
     * @return The decision as it now stands.
     */
    suspend fun assign(caller: CatalogCaller, name: String, memberId: String, decision: Decision?): Assignment

    /** Makes a personal skill a team skill: owner, pin and audience cleared. */
    suspend fun promote(caller: CatalogCaller, name: String): Skill

    /** The caller's personal skills. */
    suspend fun mine(caller: CatalogCaller): List<Skill>

    /** One of the caller's personal skills and its versions. */
    suspend fun myVersions(caller: CatalogCaller, name: String): SkillDetail

    /** Pins a version of one of the caller's personal skills. */
    suspend fun pinMine(caller: CatalogCaller, name: String, version: String): Skill

    /** Unpins, retires or restores one of the caller's personal skills. */
    suspend fun changeMine(caller: CatalogCaller, name: String, change: SkillChange): Skill
}

/** [ManageSkillCatalogUseCase]: input checked first, state rules inside the organization's write transaction. */
@Suppress("TooManyFunctions") // One operation per route of the catalog.
internal class ManageSkillCatalogUseCaseImpl(
    private val repository: SkillsRepository,
    private val members: MemberDirectory,
    private val audit: OrgAuditTrail,
    private val clock: Clock,
) : ManageSkillCatalogUseCase {
    override suspend fun catalog(caller: CatalogCaller): List<Skill> {
        CatalogAccess.requireConsoleAdministrator(caller)
        return repository.read(caller.orgId) { catalog(SkillLimits.ROWS) }
    }

    override suspend fun versions(caller: CatalogCaller, name: String): SkillDetail {
        CatalogAccess.requireConsoleAdministrator(caller)
        ArtifactTree.checkName(name, KIND)
        return repository.read(caller.orgId) {
            val skill = skill(name) ?: throw CatalogNotFoundException()
            SkillDetail(skill, versions(skill.id, SkillLimits.VERSIONS.toInt()))
        }
    }

    override fun authorizePin(caller: CatalogCaller) {
        CatalogAccess.requirePublisher(caller, SkillScopes.PUBLISH)
        CatalogAccess.requireKeyScope(caller, SkillScopes.PIN)
    }

    override suspend fun pin(caller: CatalogCaller, name: String, version: String): Skill {
        authorizePin(caller)
        ArtifactTree.checkVersion(version, KIND)
        return write(caller, name, Ownership.Team) { skill, now, events ->
            pin(skill, versionOf(skill, version), now, events)
        }
    }

    override suspend fun change(caller: CatalogCaller, name: String, change: SkillChange): Skill {
        CatalogAccess.requireConsoleAdministrator(caller)
        return write(caller, name, Ownership.Team) { skill, now, events -> apply(skill, change, now, events) }
    }

    override suspend fun retireVersion(caller: CatalogCaller, name: String, version: String): SkillVersion {
        CatalogAccess.requireConsoleAdministrator(caller)
        ArtifactTree.checkName(name, KIND)
        ArtifactTree.checkVersion(version, KIND)
        val events = mutableListOf<CatalogEvent>()
        val retired = repository.write(caller.orgId) {
            val skill = owned(name, Ownership.Team)
            val target = versionOf(skill, version)
            if (skill.pinnedVersionId == target.id) {
                throw CatalogConflictException(
                    "skill_version_pinned",
                    "Pin another version before retiring the approved one.",
                )
            }
            if (target.retired) {
                target
            } else {
                val now = clock.now()
                retireVersion(target.id, now)
                events += CatalogEvent("skill.version.retired", "skill_version", target.id)
                target.copy(retiredAt = now)
            }
        }
        audit.recordAll(caller, events)
        return retired
    }

    override suspend fun setAudience(caller: CatalogCaller, name: String, allMembers: Boolean): Skill {
        CatalogAccess.requireConsoleAdministrator(caller)
        return write(caller, name, Ownership.Team) { skill, now, events -> audience(skill, allMembers, now, events) }
    }

    override suspend fun assignments(caller: CatalogCaller, name: String): List<MemberDecision> {
        CatalogAccess.requireConsoleAdministrator(caller)
        ArtifactTree.checkName(name, KIND)
        return repository.read(caller.orgId) {
            val skill = skill(name) ?: throw CatalogNotFoundException()
            assignments(skill.id, SkillLimits.ROWS)
        }
    }

    override suspend fun assign(
        caller: CatalogCaller,
        name: String,
        memberId: String,
        decision: Decision?,
    ): Assignment {
        CatalogAccess.requireConsoleAdministrator(caller)
        ArtifactTree.checkName(name, KIND)
        if (!members.isActiveMember(caller.orgId, memberId)) throw CatalogNotFoundException("Member not found.")
        val events = mutableListOf<CatalogEvent>()
        val saved = repository.write(caller.orgId) {
            val skill = owned(name, Ownership.Team)
            val current = assignment(skill.id, memberId)
            val id = if (current == null) Uuid.random() else current.id
            val row = Assignment(id, skill.id, memberId, decision, clock.now())
            // Nothing changes: no write, no event (clearing a decision never set included).
            if ((current?.decision) == decision) return@write current ?: row
            saveAssignment(row, caller.memberId)
            val event = when {
                decision == null -> "skill.access.cleared"
                decision == Decision.ALLOW -> "skill.access.allowed"
                else -> "skill.access.denied"
            }
            events += CatalogEvent(event, "skill_assignment", row.id)
            row
        }
        audit.recordAll(caller, events)
        return saved
    }

    override suspend fun promote(caller: CatalogCaller, name: String): Skill {
        CatalogAccess.requireConsoleAdministrator(caller)
        ArtifactTree.checkName(name, KIND)
        val events = mutableListOf<CatalogEvent>()
        val promoted = repository.write(caller.orgId) {
            val skill = skill(name) ?: throw CatalogNotFoundException()
            if (skill.ownerId == null) {
                throw CatalogConflictException("skill_not_personal", "This skill already belongs to the team.")
            }
            if (countTeamSkills() >= SkillLimits.SKILLS) {
                throw CatalogConflictException("skill_limit", "The catalog is limited to ${SkillLimits.SKILLS} skills.")
            }
            val team = skill.copy(
                ownerId = null,
                pinnedVersionId = null,
                pinnedVersion = null,
                allMembers = false,
                updatedAt = clock.now(),
            )
            updateSkill(team)
            events += CatalogEvent("skill.promoted", "skill", skill.id)
            team
        }
        audit.recordAll(caller, events)
        return promoted
    }

    override suspend fun mine(caller: CatalogCaller): List<Skill> {
        PersonalAccess.requireOwner(caller, SkillScopes.READ)
        return repository.read(caller.orgId) { personal(caller.memberId, SkillLimits.PERSONAL.toInt()) }
    }

    override suspend fun myVersions(caller: CatalogCaller, name: String): SkillDetail {
        PersonalAccess.requireOwner(caller, SkillScopes.READ)
        ArtifactTree.checkName(name, KIND)
        return repository.read(caller.orgId) {
            val skill = owned(name, Ownership.Personal(caller.memberId))
            SkillDetail(skill, versions(skill.id, SkillLimits.VERSIONS.toInt()))
        }
    }

    override suspend fun pinMine(caller: CatalogCaller, name: String, version: String): Skill {
        PersonalAccess.requireOwner(caller, SkillScopes.WRITE)
        ArtifactTree.checkVersion(version, KIND)
        return write(caller, name, Ownership.Personal(caller.memberId)) { skill, now, events ->
            pin(skill, versionOf(skill, version), now, events)
        }
    }

    override suspend fun changeMine(caller: CatalogCaller, name: String, change: SkillChange): Skill {
        PersonalAccess.requireOwner(caller, SkillScopes.WRITE)
        return write(caller, name, Ownership.Personal(caller.memberId)) { skill, now, events ->
            apply(skill, change, now, events)
        }
    }

    private suspend fun SkillsStore.apply(
        skill: Skill,
        change: SkillChange,
        now: Instant,
        events: MutableList<CatalogEvent>,
    ): Skill = when (change) {
        SkillChange.UNPIN -> unpin(skill, now, events)
        SkillChange.RETIRE -> retire(skill, now, events)
        SkillChange.RESTORE -> restore(skill, now, events)
    }

    /** One change of one skill in the organization's write transaction, audited once committed. */
    private suspend fun write(
        caller: CatalogCaller,
        name: String,
        ownership: Ownership,
        change: suspend SkillsStore.(Skill, Instant, MutableList<CatalogEvent>) -> Skill,
    ): Skill {
        ArtifactTree.checkName(name, KIND)
        val events = mutableListOf<CatalogEvent>()
        val changed = repository.write(caller.orgId) { change(owned(name, ownership), clock.now(), events) }
        audit.recordAll(caller, events)
        return changed
    }

    private companion object {
        const val KIND = "skill"
    }
}
