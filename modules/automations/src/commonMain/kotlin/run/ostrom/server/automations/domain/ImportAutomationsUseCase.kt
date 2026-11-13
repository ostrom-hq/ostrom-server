// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.domain

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * An automation another system defined (TeamCode's, through `ostrom import teamcode`).
 *
 * @property sourceKey What it is in that system (`teamcode/<id>`): its id in Ostrom is derived from it and the
 *   organization, so a second import finds it.
 * @property owner The Ostrom member it belongs to.
 * @property definition What it does.
 * @property enabled Whether to arm it: null leaves a new one paused and an existing one as it is.
 */
data class ImportedAutomation(
    val sourceKey: String,
    val owner: String,
    val definition: AutomationDefinition,
    val enabled: Boolean?,
)

/** What an import did, or would do, with an automation. */
enum class AutomationImport {
    /** It did not exist: created. */
    CREATED,

    /** Its definition or its state differed: saved. */
    UPDATED,

    /** It is already as imported. */
    UNCHANGED,
}

/** Saves the automations another system defined. */
interface ImportAutomationsUseCase {
    /**
     * Creates the automation, saves it when its definition or [ImportedAutomation.enabled] differ, or leaves it alone.
     * The definition is checked as a save checks it; in a dry run, without the workflow of a workflow action, which
     * the same import may create first.
     *
     * @param orgId The organization.
     * @param automation The automation.
     * @param dryRun Writes nothing.
     * @return What it did, or would do.
     *
     * @throws InvalidAutomationException When the definition is refused, or the automation is another member's.
     * @throws AutomationLimitException When the owner already has as many automations as a member may.
     */
    suspend fun import(orgId: Uuid, automation: ImportedAutomation, dryRun: Boolean): AutomationImport
}

/**
 * [ImportAutomationsUseCase] on the repository.
 *
 * @param repository The automations.
 * @param clock The time.
 * @param workflows The workflows a workflow action is checked against.
 */
class ImportAutomationsUseCaseImpl(
    private val repository: AutomationsRepository,
    private val clock: Clock,
    private val workflows: AutomationWorkflows = NoWorkflows,
) : ImportAutomationsUseCase {
    override suspend fun import(orgId: Uuid, automation: ImportedAutomation, dryRun: Boolean): AutomationImport {
        val definition = AutomationRules.check(automation.definition)
        val workflowId = definition.action.workflowId
        if (!dryRun && workflowId != null) {
            WorkflowActionRules.checkInputs(definition.action, workflows.inputs(orgId, workflowId))
        }
        val id = importedAutomationId(orgId, automation.sourceKey)
        val current = repository.get(orgId, id)
        val outcome = when {
            current == null -> AutomationImport.CREATED
            current.owner != automation.owner -> throw InvalidAutomationException(OTHER_OWNER)
            current.definition == definition && (automation.enabled ?: current.enabled) == current.enabled ->
                AutomationImport.UNCHANGED
            else -> AutomationImport.UPDATED
        }
        if (dryRun || outcome == AutomationImport.UNCHANGED) return outcome
        val now = clock.now().truncatedToMillis()
        if (current == null) {
            val enabled = automation.enabled == true
            val created = Automation(
                id = id,
                orgId = orgId,
                owner = automation.owner,
                definition = definition,
                enabled = enabled,
                version = 1,
                secretGeneration = 1,
                previousSecretValidUntil = null,
                nextRunAt = if (enabled) AutomationRules.nextRun(definition, now) else null,
                createdAt = now,
                updatedAt = now,
            )
            val limit = ManageAutomationsUseCaseImpl.MAX_PER_MEMBER
            if (!repository.createWithinLimit(created, limit)) throw AutomationLimitException(limit)
        } else {
            val enabled = automation.enabled ?: current.enabled
            val changed = current.copy(
                definition = definition,
                enabled = enabled,
                version = if (current.definition == definition) current.version else current.version + 1,
                nextRunAt = if (enabled) AutomationRules.nextRun(definition, now) else null,
                updatedAt = now,
            )
            repository.update(changed, current) ?: throw StaleAutomationException()
        }
        return outcome
    }
}

/**
 * The id of an imported automation: a digest of the organization and its [sourceKey], laid out as a version 5 UUID,
 * so that every import of the same automation into the same organization finds it.
 *
 * @param orgId The organization.
 * @param sourceKey What it is in the system it comes from.
 * @return Its id.
 */
suspend fun importedAutomationId(orgId: Uuid, sourceKey: String): Uuid {
    val digest = CryptographyProvider.Default.get(SHA256).hasher()
        .hash("ostrom-import-automation\u0000$orgId\u0000$sourceKey".encodeToByteArray())
        .copyOf(UUID_BYTES)
    digest[VERSION_BYTE] = (digest[VERSION_BYTE].toInt() and LOW_NIBBLE or VERSION_5).toByte()
    digest[VARIANT_BYTE] = (digest[VARIANT_BYTE].toInt() and VARIANT_MASK or VARIANT_RFC).toByte()
    return Uuid.fromByteArray(digest)
}

// The imported automation exists in Ostrom under another owner: the import never moves an automation.
private const val OTHER_OWNER =
    "It belongs to another member in Ostrom: delete it there to import it again under the mapped owner."
private const val UUID_BYTES = 16
private const val VERSION_BYTE = 6
private const val VARIANT_BYTE = 8
private const val LOW_NIBBLE = 0x0f
private const val VERSION_5 = 0x50
private const val VARIANT_MASK = 0x3f
private const val VARIANT_RFC = 0x80
