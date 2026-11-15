// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.importer.domain

import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.secrets.domain.EnvironmentActor
import run.ostrom.server.secrets.domain.EnvironmentAssignment
import run.ostrom.server.secrets.domain.EnvironmentBinding
import run.ostrom.server.secrets.domain.EnvironmentExclusion
import run.ostrom.server.secrets.domain.EnvironmentRules
import run.ostrom.server.secrets.domain.EnvironmentValueCheck
import run.ostrom.server.secrets.domain.EnvironmentVariant
import run.ostrom.server.secrets.domain.EnvironmentVariantUpdate
import run.ostrom.server.secrets.domain.ManageEnvironmentAssignmentsUseCase
import run.ostrom.server.secrets.domain.ManageEnvironmentVariantsUseCase
import run.ostrom.server.secrets.domain.MemberRole
import run.ostrom.server.secrets.domain.NewEnvironmentVariant
import run.ostrom.server.secrets.domain.PersonalEnvironmentVariantsUseCase
import kotlin.uuid.Uuid

/**
 * The environment use cases the import writes through: it never seals a value itself, the secrets module does.
 *
 * @property variants The team's variants.
 * @property assignments The members' selections and exclusions.
 * @property personal A member's own variants, written as them.
 * @property check Whether a stored value equals TeamCode's, to leave an unchanged one alone.
 */
data class EnvironmentPorts(
    val variants: ManageEnvironmentVariantsUseCase,
    val assignments: ManageEnvironmentAssignmentsUseCase,
    val personal: PersonalEnvironmentVariantsUseCase,
    val check: EnvironmentValueCheck,
)

/**
 * Imports TeamCode's environment variants (`variants` of kind `ENV`) through the secrets module's environment use
 * cases: each value is sealed there under the instance key, never logged nor reported. A variable name the secrets
 * module reserves is refused exactly as on creation ([EnvironmentRules.requireVariable]), in a dry run too. A variant
 * of the same variable and name an admin created by hand is reported skipped. A personal variant becomes its mapped
 * owner's, written as them; the team's are selected for each mapped member as in TeamCode, then the exclusions
 * applied. A second run changes nothing: a value is rewritten only when it differs.
 *
 * @param ports The environment use cases.
 */
internal class TeamCodeEnvironmentImporter(private val ports: EnvironmentPorts) {
    /** Imports [catalog]'s variables, exclusions and assignments, appending one line per item to [items]. */
    suspend fun import(run: CatalogRun, catalog: TeamCodeCatalog, items: MutableList<ImportItem>) {
        val admin = EnvironmentActor(run.caller, run.principal)
        val stored = ports.variants.list(run.caller, personal = false) +
            ports.variants.list(run.caller, personal = true)
        val owned = run.owned[ImportRecordKind.VARIABLE].orEmpty()
        val imported = mutableMapOf<String, ImportedVariable>()
        for (variable in catalog.variables) {
            val owner = variable.ownerId?.let(run.memberMap::get)
            val personal = if (variable.ownerId == null) "" else " (personal, ${owner ?: variable.ownerId})"
            val label = "${variable.variable}/${variable.name}$personal"
            if (variable.ownerId != null && owner == null) {
                items += ImportItem(ImportKind.VARIABLE, label, ImportAction.SKIPPED, NO_OWNER)
                continue
            }
            guarded(items, ImportKind.VARIABLE, label) {
                val outcome = variable(run, admin, variable, owner, stored, owned[variable.id])
                imported[variable.id] = outcome.first
                items += ImportItem(ImportKind.VARIABLE, label, outcome.second)
            }
        }
        val (assigned, excluded) = ports.assignments.list(run.caller, null)
        for (exclusion in catalog.variableExclusions) exclusion(run, admin, exclusion, excluded, items)
        val byId = catalog.variables.associateBy { it.id }
        for (assignment in catalog.variableAssignments) {
            val source = byId[assignment.variantId]
            // A personal variant is selected for its owner when it is created: TeamCode's row for it says the same.
            if (source == null || source.ownerId != null) continue
            assignment(run, admin, assignment, source, imported[assignment.variantId], assigned, items)
        }
    }

    // The rules of one variant, in the secrets module's order.
    @Suppress("LongParameterList", "CyclomaticComplexMethod", "ReturnCount")
    private suspend fun variable(
        run: CatalogRun,
        admin: EnvironmentActor,
        source: TeamCodeVariable,
        owner: Uuid?,
        stored: List<EnvironmentVariant>,
        recorded: Uuid?,
    ): Pair<ImportedVariable, ImportAction> {
        // The secrets module's own checks, before anything is read or written: a reserved name is refused as on
        // creation, a dry run included.
        EnvironmentRules.requireVariable(source.variable)
        EnvironmentRules.requireName(source.name)
        source.value?.let(EnvironmentRules::requireValue)
        val binding = if (source.perMember) EnvironmentBinding.PER_MEMBER else EnvironmentBinding.SHARED
        val actor = owner?.let { EnvironmentActor(Caller(run.caller.orgId, it, MemberRole.MEMBER), run.principal) }
        val existing = recorded?.let { id -> stored.firstOrNull { it.id == id } }
        if (existing == null) {
            // Names are unique per variable and owner, ignoring case.
            val key = Triple(source.variable, source.name.trim().lowercase(), owner)
            if (stored.any { Triple(it.variable, it.name.lowercase(), it.ownerMemberId) == key }) {
                throw CatalogImportRefusal(FOREIGN)
            }
            if (run.dryRun) return ImportedVariable(null, binding) to ImportAction.PLANNED
            val new = NewEnvironmentVariant(source.variable, source.name, binding, source.value)
            val created = if (actor == null) ports.variants.create(admin, new) else ports.personal.create(actor, new)
            val recordName = "${source.variable}/${created.name}"
            run.record(ImportRecord(ImportRecordKind.VARIABLE, source.id, recordName, created.id))
            return ImportedVariable(created.id, binding) to ImportAction.CREATED
        }
        if (existing.ownerMemberId != owner || existing.binding != binding) throw CatalogImportRefusal(MOVED)
        val name = source.name.trim().takeIf { it != existing.name }
        val value = source.value?.takeUnless { ports.check.matches(run.caller.orgId, existing.id, null, it) }
        val action = when {
            name == null && value == null -> ImportAction.UNCHANGED
            run.dryRun -> ImportAction.PLANNED
            else -> {
                val update = EnvironmentVariantUpdate(existing.version, name, value)
                if (actor == null) {
                    ports.variants.update(admin, existing.id, update)
                } else {
                    ports.personal.update(actor, existing.id, update)
                }
                ImportAction.UPDATED
            }
        }
        return ImportedVariable(existing.id, binding) to action
    }

    private suspend fun exclusion(
        run: CatalogRun,
        admin: EnvironmentActor,
        exclusion: TeamCodeVariableExclusion,
        excluded: List<EnvironmentExclusion>,
        items: MutableList<ImportItem>,
    ) {
        val member = run.memberMap[exclusion.memberId]
        val name = "${exclusion.variable}/${member ?: exclusion.memberId}"
        if (member == null) {
            items += ImportItem(ImportKind.EXCLUSION, name, ImportAction.SKIPPED, run.unmapped(exclusion.memberId))
            return
        }
        guarded(items, ImportKind.EXCLUSION, name) {
            EnvironmentRules.requireVariable(exclusion.variable)
            val action = when {
                EnvironmentExclusion(member, exclusion.variable) in excluded -> ImportAction.UNCHANGED
                run.dryRun -> ImportAction.PLANNED
                else -> {
                    ports.assignments.exclude(admin, member, exclusion.variable)
                    ImportAction.CREATED
                }
            }
            items += ImportItem(ImportKind.EXCLUSION, name, action)
        }
    }

    @Suppress("LongParameterList") // The run, TeamCode's row and its variant, what was imported and what is stored.
    private suspend fun assignment(
        run: CatalogRun,
        admin: EnvironmentActor,
        assignment: TeamCodeVariableAssignment,
        source: TeamCodeVariable,
        variant: ImportedVariable?,
        assigned: List<EnvironmentAssignment>,
        items: MutableList<ImportItem>,
    ) {
        val member = run.memberMap[assignment.memberId]
        val name = "${source.variable}/${member ?: assignment.memberId}"
        if (member == null || variant == null) {
            val refusal = if (member == null) run.unmapped(assignment.memberId) else "Its variant was not imported."
            items += ImportItem(ImportKind.ASSIGNMENT, name, ImportAction.SKIPPED, refusal)
            return
        }
        val id = variant.id
        if (id == null) {
            // A variant a dry run would create: its selection would be written too.
            items += ImportItem(ImportKind.ASSIGNMENT, name, ImportAction.PLANNED)
            return
        }
        guarded(items, ImportKind.ASSIGNMENT, name) {
            val current = assigned.firstOrNull { (it.memberId to it.variable) == (member to source.variable) }
            val value = assignment.memberValue.takeIf { variant.binding == EnvironmentBinding.PER_MEMBER }
            val same = current?.variantId == id &&
                (value == null || ports.check.matches(run.caller.orgId, id, member, value))
            val action = when {
                same -> ImportAction.UNCHANGED
                run.dryRun -> ImportAction.PLANNED
                else -> {
                    ports.assignments.assign(admin, member, source.variable, id, value)
                    if (current == null) ImportAction.CREATED else ImportAction.UPDATED
                }
            }
            items += ImportItem(ImportKind.ASSIGNMENT, name, action)
        }
    }

    /**
     * A variant as the run left it.
     *
     * @property id Its id in Ostrom; null when a dry run would create it.
     * @property binding Where its value lives.
     */
    private class ImportedVariable(val id: Uuid?, val binding: EnvironmentBinding)

    private companion object {
        const val NO_OWNER = "Its owner has no Ostrom member."
        const val FOREIGN = "A variant of this variable and name exists and was not imported from TeamCode."
        const val MOVED = "It was promoted, or its binding changed, since it was imported: delete it in Ostrom to " +
            "import it again."
    }
}
