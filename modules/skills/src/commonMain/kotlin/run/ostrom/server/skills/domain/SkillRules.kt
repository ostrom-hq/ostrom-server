// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.skills.domain

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.auditDetails
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The bounds of the skill catalog (TeamCode's). */
object SkillLimits {
    /** Team skills per organization. */
    const val SKILLS: Long = 500

    /** Personal skills per member; they do not count toward [SKILLS]. */
    const val PERSONAL: Long = 50

    /** Versions per skill. */
    const val VERSIONS: Long = 200

    /** Files per artifact. */
    const val FILES: Int = 200

    /** Bytes of content per artifact: 5 MiB. */
    const val BYTES: Long = 5L * 1024 * 1024

    /** Explicit decisions listed per skill, and installation rows per member. */
    const val ROWS: Int = 1_000

    /** Skills reported at once. */
    const val REPORTS: Int = 500

    /** The file every skill artifact holds at its root. */
    const val ENTRY_FILE: String = "SKILL.md"
}

/**
 * A change written to the organization's audit log once its transaction committed: metadata only, never a file,
 * a path, a digest or a label.
 *
 * @property event Its name (`skill.pinned`).
 * @property subjectType `skill`, `skill_version` or `skill_assignment`.
 * @property subjectId The subject's id.
 */
data class CatalogEvent(val event: String, val subjectType: String, val subjectId: Uuid)

/**
 * Writes [events] in [caller]'s name; a service key's events name the key (`credentialId`), so the journal tells a CI
 * write from an administrator's.
 */
internal suspend fun OrgAuditTrail.recordAll(caller: CatalogCaller, events: List<CatalogEvent>) {
    events.forEach { event ->
        record(
            OrgAuditRecord(
                orgId = caller.orgId,
                actor = caller.principal,
                event = event.event,
                subjectType = event.subjectType,
                subjectId = event.subjectId.toString(),
                details = auditDetails("credentialId" to caller.keyId),
            ),
        )
    }
}

/** Whose skill a change acts on: the catalog's own operations never touch a personal skill, an owner only theirs. */
internal sealed interface Ownership {
    /** A team skill; a personal skill is refused with `skill_personal`. */
    data object Team : Ownership

    /**
     * [ownerId]'s personal skill; any other is not found.
     *
     * @property ownerId The owner.
     */
    data class Personal(val ownerId: String) : Ownership
}

/**
 * The state changes of the catalog, applied inside one write transaction of [SkillsRepository] (TeamCode's
 * `SqlSkillStore`): each returns the audit events it caused, none for a change that changes nothing.
 */
internal object SkillRules {
    /** The skill [name] as [ownership] sees it. */
    suspend fun SkillsStore.owned(name: String, ownership: Ownership): Skill {
        val skill = skill(name) ?: throw CatalogNotFoundException()
        when (ownership) {
            Ownership.Team -> if (skill.ownerId != null) {
                throw CatalogConflictException(
                    "skill_personal",
                    "This is a member's personal skill: promote it to the team first.",
                )
            }
            is Ownership.Personal -> if (skill.ownerId != ownership.ownerId) throw CatalogNotFoundException()
        }
        return skill
    }

    /** A version of [skill] by its label. */
    suspend fun SkillsStore.versionOf(skill: Skill, label: String): SkillVersion =
        version(skill.id, label) ?: throw CatalogNotFoundException("Skill version not found.")

    /** Points [skill] at [version] unless it already is. */
    suspend fun SkillsStore.pin(
        skill: Skill,
        version: SkillVersion,
        now: Instant,
        events: MutableList<CatalogEvent>,
    ): Skill {
        if (skill.retired) {
            throw CatalogConflictException("skill_retired", "Restore this skill before pinning a version.")
        }
        if (version.retired) {
            throw CatalogConflictException("skill_version_retired", "This version is retired and cannot be pinned.")
        }
        if (skill.pinnedVersionId == version.id) return skill
        val pinned = skill.copy(pinnedVersionId = version.id, pinnedVersion = version.version, updatedAt = now)
        updateSkill(pinned)
        events += CatalogEvent("skill.pinned", "skill_version", version.id)
        return pinned
    }

    /** Sets [skill]'s audience unless it already is. */
    suspend fun SkillsStore.audience(
        skill: Skill,
        allMembers: Boolean,
        now: Instant,
        events: MutableList<CatalogEvent>,
    ): Skill {
        if (skill.allMembers == allMembers) return skill
        val changed = skill.copy(allMembers = allMembers, updatedAt = now)
        updateSkill(changed)
        val event = if (allMembers) "skill.audience.all_members" else "skill.audience.assigned_only"
        events += CatalogEvent(event, "skill", skill.id)
        return changed
    }

    /** Stops delivering [skill]. */
    suspend fun SkillsStore.unpin(skill: Skill, now: Instant, events: MutableList<CatalogEvent>): Skill {
        if (skill.pinnedVersionId == null) return skill
        val changed = skill.copy(pinnedVersionId = null, pinnedVersion = null, updatedAt = now)
        updateSkill(changed)
        events += CatalogEvent("skill.unpinned", "skill", skill.id)
        return changed
    }

    /** Removes [skill] from the catalog: unpinned, closed to publication. */
    suspend fun SkillsStore.retire(skill: Skill, now: Instant, events: MutableList<CatalogEvent>): Skill {
        if (skill.retired) return skill
        val changed = skill.copy(retiredAt = now, pinnedVersionId = null, pinnedVersion = null, updatedAt = now)
        updateSkill(changed)
        events += CatalogEvent("skill.retired", "skill", skill.id)
        return changed
    }

    /** Reopens [skill] to publication, nothing pinned. */
    suspend fun SkillsStore.restore(skill: Skill, now: Instant, events: MutableList<CatalogEvent>): Skill {
        if (!skill.retired) return skill
        val changed = skill.copy(retiredAt = null, updatedAt = now)
        updateSkill(changed)
        events += CatalogEvent("skill.restored", "skill", skill.id)
        return changed
    }

    /**
     * Publishes [label] of [name], creating the skill on its first publication (TeamCode's `publishAs` and
     * `publishVersion`, in one transaction here: the organization's catalog lock is held for the content insert,
     * which delays only that organization's catalog writes).
     *
     * @param personalOwner The owner for a personal publication, null for the team's.
     */
    @Suppress("LongParameterList", "CyclomaticComplexMethod") // The rules of one publication, read in order.
    suspend fun SkillsStore.publish(
        caller: CatalogCaller,
        orgId: Uuid,
        name: String,
        label: String,
        artifact: ValidatedArtifact,
        pin: Boolean,
        allMembers: Boolean,
        personalOwner: String?,
        now: Instant,
        events: MutableList<CatalogEvent>,
    ): Publication {
        val existing = skill(name)
        // One name space per organization: a name the team or another member uses is refused, naming nobody.
        if (existing != null && existing.ownerId != personalOwner) {
            throw CatalogConflictException("name_taken", "This skill name is already used: choose another name.")
        }
        val skill = existing ?: create(orgId, name, personalOwner, now, events)
        if (skill.retired) throw CatalogConflictException("skill_retired", "Restore this skill before publishing.")
        val known = version(skill.id, label)
        if (known != null) {
            if (known.digest != artifact.digest) {
                throw CatalogConflictException(
                    "skill_version_conflict",
                    "This version is already published with different content: publish a new version.",
                )
            }
            if (known.retired) {
                throw CatalogConflictException("skill_version_retired", "This version is retired: publish a new one.")
            }
            // A retry converges on the same state; only an actual change is audited.
            var after = if (pin) pin(skill, known, now, events) else skill
            if (allMembers) after = audience(after, true, now, events)
            return Publication(after, known, created = false)
        }
        if (countVersions(skill.id) >= SkillLimits.VERSIONS) {
            throw CatalogConflictException(
                "skill_version_limit",
                "A skill keeps at most ${SkillLimits.VERSIONS} versions.",
            )
        }
        val version = SkillVersion(
            id = Uuid.random(),
            skillId = skill.id,
            version = label,
            digest = artifact.digest,
            sizeBytes = artifact.sizeBytes,
            fileCount = artifact.entries.size,
            publishedBy = caller.memberId,
            publishedAt = now,
            retiredAt = null,
        )
        insertVersion(version, artifact)
        events += CatalogEvent("skill.version.published", "skill_version", version.id)
        var after = skill.copy(updatedAt = now)
        updateSkill(after)
        if (pin) after = pin(after, version, now, events)
        if (allMembers) after = audience(after, true, now, events)
        return Publication(after, version, created = true)
    }

    private suspend fun SkillsStore.create(
        orgId: Uuid,
        name: String,
        owner: String?,
        now: Instant,
        events: MutableList<CatalogEvent>,
    ): Skill {
        if (owner == null && countTeamSkills() >= SkillLimits.SKILLS) {
            throw CatalogConflictException("skill_limit", "The catalog is limited to ${SkillLimits.SKILLS} skills.")
        }
        if (owner != null && countPersonalSkills(owner) >= SkillLimits.PERSONAL) {
            throw CatalogConflictException(
                "skill_limit",
                "A member keeps at most ${SkillLimits.PERSONAL} personal skills.",
            )
        }
        val skill = Skill(Uuid.random(), orgId, name, owner, null, null, false, null, now, now)
        insertSkill(skill)
        events += CatalogEvent(if (owner == null) "skill.created" else "skill.personal.created", "skill", skill.id)
        return skill
    }
}
