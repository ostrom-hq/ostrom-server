// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.domain

import run.ostrom.server.hooks.domain.Hook
import run.ostrom.server.hooks.domain.HookArtifacts
import run.ostrom.server.hooks.domain.HookDefinition
import run.ostrom.server.hooks.domain.HookEvent
import run.ostrom.server.hooks.domain.ManageHookCatalogUseCase
import run.ostrom.server.hooks.domain.PublishHookVersionUseCase
import run.ostrom.server.kernel.auth.ALL_USER_SCOPES
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.skills.domain.ArtifactFile
import run.ostrom.server.skills.domain.ArtifactTree
import run.ostrom.server.skills.domain.CatalogCaller
import run.ostrom.server.skills.domain.CatalogConflictException
import run.ostrom.server.skills.domain.CatalogNotFoundException
import run.ostrom.server.skills.domain.CredentialKind
import run.ostrom.server.skills.domain.Decision
import run.ostrom.server.skills.domain.FileMode
import run.ostrom.server.skills.domain.ManageSkillCatalogUseCase
import run.ostrom.server.skills.domain.MemberDecision
import run.ostrom.server.skills.domain.PublishSkillVersionUseCase
import run.ostrom.server.skills.domain.Sha256
import run.ostrom.server.skills.domain.Skill
import run.ostrom.server.skills.domain.SkillChange
import run.ostrom.server.skills.domain.SkillLimits
import kotlin.uuid.Uuid

/**
 * The skill catalog's use cases the import publishes through.
 *
 * @property publish Publishes versions.
 * @property manage Reads the catalog, pins, sets the audience and the decisions.
 * @property sha256 The hash of the catalog's digests, to tell an already published version.
 */
data class SkillPorts(
    val publish: PublishSkillVersionUseCase,
    val manage: ManageSkillCatalogUseCase,
    val sha256: Sha256,
)

/**
 * The hook catalog's use cases the import publishes through.
 *
 * @property publish Publishes versions.
 * @property manage Reads the catalog, pins, sets the audience and the decisions.
 * @property sha256 The hash of the catalog's digests.
 */
data class HookPorts(
    val publish: PublishHookVersionUseCase,
    val manage: ManageHookCatalogUseCase,
    val sha256: Sha256,
)

/**
 * How a catalog run goes, and what it knows.
 *
 * @property caller The admin running the import.
 * @property principal The admin's credential, named in the catalogs' audit log.
 * @property memberMap TeamCode member id to Ostrom member id.
 * @property usernames TeamCode member id to username, for the report.
 * @property dryRun Writes nothing.
 * @property owned What the import created before, by kind then TeamCode id.
 * @property record Records what it creates.
 */
internal class CatalogRun(
    val caller: Caller,
    val principal: AuthenticatedPrincipal,
    val memberMap: Map<String, Uuid>,
    val usernames: Map<String, String>,
    val dryRun: Boolean,
    val owned: Map<ImportRecordKind, Map<String, Uuid>>,
    val record: Recorder,
) {
    /** The admin as the catalogs see them: an administrator in the console, the import being theirs. */
    val admin: CatalogCaller = catalogCaller(caller.memberId, administrator = true)

    /** A member as the catalogs see them, for their personal skills. */
    fun member(memberId: Uuid): CatalogCaller = catalogCaller(memberId, administrator = false)

    /** The line's name of a TeamCode member: their Ostrom id when mapped, their username otherwise. */
    fun unmapped(teamCodeId: String): String = "No Ostrom member is mapped to ${usernames[teamCodeId] ?: teamCodeId}."

    private fun catalogCaller(memberId: Uuid, administrator: Boolean) = CatalogCaller(
        orgId = caller.orgId,
        memberId = memberId.toString(),
        administrator = administrator,
        credential = CredentialKind.CONSOLE,
        scopes = setOf(ALL_USER_SCOPES),
        workstation = null,
        credentialId = null,
        principal = principal,
    )

    /** The action of a write: planned in a dry run. */
    fun wrote(created: Boolean): ImportAction = when {
        dryRun -> ImportAction.PLANNED
        created -> ImportAction.CREATED
        else -> ImportAction.UPDATED
    }
}

/** A TeamCode row the import cannot carry: reported as skipped with the reason. */
internal class CatalogImportRefusal(message: String) : OstromException(ErrorKind.INVALID_REQUEST, message)

/**
 * Imports TeamCode's skills and hooks through the skill and hook catalogs' use cases: each version read is published
 * (a version already published with the same content is left alone, as the catalog does), the pinned one pinned, the
 * audience and the members' decisions set. A skill or hook of the same name an admin created by hand is reported
 * skipped. A retired skill is not imported. The report names a skill or hook by its name, never a file or its content.
 *
 * @param skills The skill catalog, or null when this server keeps none.
 * @param hooks The hook catalog, or null when this server keeps none.
 */
internal class TeamCodeCatalogImporter(private val skills: SkillPorts?, private val hooks: HookPorts?) {
    /** Imports [catalog]'s skills then hooks, appending one line per item to [items]. */
    suspend fun import(run: CatalogRun, catalog: TeamCodeCatalog, items: MutableList<ImportItem>) {
        if (catalog.skills.isNotEmpty()) importSkills(run, catalog.skills, items)
        if (catalog.hooks.isNotEmpty()) importHooks(run, catalog.hooks, items)
    }

    private suspend fun importSkills(run: CatalogRun, teamCode: List<TeamCodeSkill>, items: MutableList<ImportItem>) {
        val ports = skills
        if (ports == null) {
            teamCode.forEach { items += ImportItem(ImportKind.SKILL, it.name, ImportAction.SKIPPED, NO_SKILLS) }
            return
        }
        val existing = ports.manage.catalog(run.admin).associateBy { it.name }
        val owned = run.owned[ImportRecordKind.SKILL].orEmpty()
        for (skill in teamCode) {
            var imported = false
            guarded(items, ImportKind.SKILL, skill.name) {
                items += SkillImport(ports, run, skill, existing[skill.name], owned[skill.id]).import()
                imported = true
            }
            // A personal skill reaches its owner only: TeamCode's decisions on it mean nothing.
            if (skill.ownerId == null) {
                val access = DecisionAccess(
                    read = { ports.manage.assignments(run.admin, skill.name) },
                    assign = { member, decision -> ports.manage.assign(run.admin, skill.name, member, decision) },
                )
                decisions(run, skill.name, skill.decisions, imported, items, access)
            }
        }
    }

    private suspend fun importHooks(run: CatalogRun, teamCode: List<TeamCodeHook>, items: MutableList<ImportItem>) {
        val ports = hooks
        if (ports == null) {
            teamCode.forEach { items += ImportItem(ImportKind.HOOK, it.name, ImportAction.SKIPPED, NO_HOOKS) }
            return
        }
        val existing = ports.manage.catalog(run.admin).associateBy { it.name }
        val owned = run.owned[ImportRecordKind.HOOK].orEmpty()
        for (hook in teamCode) {
            var imported = false
            guarded(items, ImportKind.HOOK, hook.name) {
                items += importHook(ports, run, hook, existing[hook.name], owned[hook.id])
                imported = true
            }
            val access = DecisionAccess(
                read = { ports.manage.assignments(run.admin, hook.name) },
                assign = { member, decision -> ports.manage.assign(run.admin, hook.name, member, decision) },
            )
            decisions(run, hook.name, hook.decisions, imported, items, access)
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // The steps of one hook, in the catalog's order.
    private suspend fun importHook(
        ports: HookPorts,
        run: CatalogRun,
        hook: TeamCodeHook,
        existing: Hook?,
        recorded: Uuid?,
    ): ImportItem {
        if (existing != null && existing.id != recorded) throw CatalogImportRefusal(foreign("hook", hook.name))
        if (hook.versions.isEmpty()) throw CatalogImportRefusal(NO_VERSION)
        val known = existing?.let {
            ports.manage.versions(run.admin, hook.name).versions.associateBy { it.version }
        }.orEmpty()
        var changed = false
        for (version in hook.versions) {
            val definition = definitionOf(version)
            val files = filesOf(version.files)
            val digest = HookArtifacts.validate(definition, files, ports.sha256).digest
            val stored = known[version.version]
            if (stored != null) {
                if (stored.digest != digest) throw CatalogConflictException("hook_version_conflict", CONFLICT)
                continue
            }
            changed = true
            if (!run.dryRun) {
                val publication = ports.publish(run.admin, hook.name, version.version, definition, files, false)
                if (existing == null) {
                    run.record(ImportRecord(ImportRecordKind.HOOK, hook.id, hook.name, publication.hook.id))
                }
            }
        }
        val pinned = existing?.pinnedVersion
        if (hook.pinnedVersion != pinned) {
            changed = true
            if (!run.dryRun) {
                val target = hook.pinnedVersion
                if (target == null) {
                    ports.manage.unpin(run.admin, hook.name)
                } else {
                    ports.manage.pin(run.admin, hook.name, target)
                }
            }
        }
        if (hook.allMembers != (existing?.allMembers ?: false)) {
            changed = true
            if (!run.dryRun) ports.manage.setAudience(run.admin, hook.name, hook.allMembers)
        }
        val action = if (changed) run.wrote(existing == null) else ImportAction.UNCHANGED
        return ImportItem(ImportKind.HOOK, hook.name, action, omitted(hook.omittedVersions))
    }

    /**
     * The members' decisions on an imported skill or hook: each mapped member's is set unless it already is; one of a
     * member Ostrom does not have, or of an item not imported, is reported skipped.
     */
    @Suppress("LongParameterList") // The run, the item and its decisions, whether it was imported, the report, access.
    private suspend fun decisions(
        run: CatalogRun,
        item: String,
        decisions: List<TeamCodeDecision>,
        imported: Boolean,
        items: MutableList<ImportItem>,
        access: DecisionAccess,
    ) {
        if (decisions.isEmpty()) return
        val current = if (imported) access.current() else emptyMap()
        for (teamCode in decisions) {
            val member = run.memberMap[teamCode.memberId]
            val name = "$item/${member ?: teamCode.memberId}"
            val decision = Decision.fromWire(teamCode.decision)
            if (!imported || member == null || decision == null) {
                val refusal = when {
                    !imported -> NOT_IMPORTED
                    member == null -> run.unmapped(teamCode.memberId)
                    else -> "TeamCode's decision ${teamCode.decision} is neither allow nor deny."
                }
                items += ImportItem(ImportKind.DECISION, name, ImportAction.SKIPPED, refusal)
                continue
            }
            guarded(items, ImportKind.DECISION, name) {
                val before = current[member.toString()]
                val action = if (before == decision) ImportAction.UNCHANGED else run.wrote(before == null)
                if (action != ImportAction.UNCHANGED && !run.dryRun) access.assign(member.toString(), decision)
                items += ImportItem(ImportKind.DECISION, name, action)
            }
        }
    }

    /**
     * How the decisions of one skill or hook are read and set.
     *
     * @property read Its decisions; refused with a 404 when a dry run would create it.
     * @property assign Sets a member's decision.
     */
    private class DecisionAccess(
        val read: suspend () -> List<MemberDecision>,
        val assign: suspend (String, Decision) -> Unit,
    ) {
        /** The decisions already set; none for an item that does not exist yet. */
        suspend fun current(): Map<String, Decision> = try {
            read().associate { it.memberId to it.decision }
        } catch (_: CatalogNotFoundException) {
            emptyMap()
        }
    }

    /**
     * One skill's import.
     *
     * @param ports The catalog.
     * @param run The run.
     * @param skill TeamCode's skill.
     * @param existing The skill of that name in Ostrom, or null.
     * @param recorded The id of the skill the import created for it before, or null.
     */
    private class SkillImport(
        private val ports: SkillPorts,
        private val run: CatalogRun,
        private val skill: TeamCodeSkill,
        private val existing: Skill?,
        private val recorded: Uuid?,
    ) {
        private val owner: Uuid? = skill.ownerId?.let { run.memberMap[it] ?: throw CatalogImportRefusal(NO_OWNER) }
        private val publisher: CatalogCaller = owner?.let(run::member) ?: run.admin

        @Suppress("CyclomaticComplexMethod") // The steps of one skill, in the catalog's order.
        suspend fun import(): ImportItem {
            if (skill.retired) throw CatalogImportRefusal(RETIRED)
            if (existing != null && (existing.id != recorded || existing.ownerId != owner?.toString())) {
                throw CatalogImportRefusal(foreign("skill", skill.name))
            }
            if (skill.versions.isEmpty()) throw CatalogImportRefusal(NO_VERSION)
            val known = existing?.let {
                ports.manage.versions(run.admin, skill.name).versions.associateBy { it.version }
            }.orEmpty()
            var changed = false
            for (version in skill.versions) {
                val files = filesOf(version.files)
                val entries = ArtifactTree.entries(files, SkillLimits.FILES, SkillLimits.BYTES, KIND, ports.sha256)
                val stored = known[version.version]
                if (stored != null) {
                    if (stored.digest != ArtifactTree.digest(entries, ports.sha256)) {
                        throw CatalogConflictException("skill_version_conflict", CONFLICT)
                    }
                    continue
                }
                changed = true
                if (!run.dryRun) publish(version.version, files)
            }
            if (skill.pinnedVersion != existing?.pinnedVersion) {
                changed = true
                if (!run.dryRun) pin(skill.pinnedVersion)
            }
            if (owner == null && skill.allMembers != (existing?.allMembers ?: false)) {
                changed = true
                if (!run.dryRun) ports.manage.setAudience(run.admin, skill.name, skill.allMembers)
            }
            val action = if (changed) run.wrote(existing == null) else ImportAction.UNCHANGED
            val label = if (owner == null) skill.name else "${skill.name} (personal, $owner)"
            return ImportItem(ImportKind.SKILL, label, action, omitted(skill.omittedVersions))
        }

        private suspend fun publish(version: String, files: List<ArtifactFile>) {
            val publication = if (owner == null) {
                ports.publish(publisher, skill.name, version, files, pin = false, allMembers = false)
            } else {
                ports.publish.personal(publisher, skill.name, version, files, pin = false)
            }
            if (existing == null) {
                run.record(ImportRecord(ImportRecordKind.SKILL, skill.id, skill.name, publication.skill.id))
            }
        }

        private suspend fun pin(version: String?) {
            when {
                version != null && owner == null -> ports.manage.pin(publisher, skill.name, version)
                version != null -> ports.manage.pinMine(publisher, skill.name, version)
                owner == null -> ports.manage.change(publisher, skill.name, SkillChange.UNPIN)
                else -> ports.manage.changeMine(publisher, skill.name, SkillChange.UNPIN)
            }
        }
    }

    private companion object {
        const val KIND = "skill"
        const val NO_SKILLS = "This server keeps no skill catalog."
        const val NO_HOOKS = "This server keeps no hook catalog."
        const val NO_VERSION = "TeamCode has no version of it to import."
        const val NO_OWNER = "Its owner has no Ostrom member."
        const val NOT_IMPORTED = "Its skill or hook was not imported."
        const val RETIRED = "TeamCode retired it."
        const val CONFLICT = "This version is already published in Ostrom with different content: publish a new " +
            "version in TeamCode, or delete it in Ostrom."

        fun foreign(kind: String, name: String) = "A $kind named $name exists and was not imported from TeamCode."

        fun omitted(count: Int): String? = when {
            count <= 0 -> null
            count == 1 -> "1 older version stays in TeamCode."
            else -> "$count older versions stay in TeamCode."
        }

        fun filesOf(files: List<TeamCodeArtifactFile>): List<ArtifactFile> = files.map { file ->
            val mode = FileMode.entries.firstOrNull { it.wire == file.mode }
                ?: throw CatalogImportRefusal("Its file ${file.path} has a mode Ostrom does not keep (${file.mode}).")
            ArtifactFile(file.path, mode, file.content)
        }

        fun definitionOf(version: TeamCodeHookVersion): HookDefinition {
            val events = version.events.map { wire ->
                HookEvent.fromWire(wire) ?: throw CatalogImportRefusal("Ostrom does not run hooks on $wire.")
            }
            return HookDefinition(events.toSet(), version.matcher, version.command, version.timeoutSeconds)
        }
    }
}
