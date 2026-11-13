// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.domain.usecases

import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.compiler.domain.models.Authorship
import run.ostrom.server.compiler.domain.models.CompileInputs
import run.ostrom.server.compiler.domain.models.DecisionDoc
import run.ostrom.server.compiler.domain.models.StoredWorkflow
import run.ostrom.server.compiler.domain.models.WorkflowDoc
import run.ostrom.server.compiler.domain.repositories.CompilerRepository
import run.ostrom.server.compiler.domain.repositories.MAX_WORKFLOWS
import run.ostrom.server.compiler.domain.repositories.WorkflowsRepository
import run.ostrom.server.compiler.domain.services.CallChecks
import run.ostrom.server.compiler.domain.services.JsText
import run.ostrom.server.compiler.domain.services.NodeConditions
import run.ostrom.server.compiler.domain.services.PackageCompiler
import run.ostrom.server.compiler.domain.services.WorkflowChecks
import run.ostrom.server.compiler.domain.services.WorkflowRepos
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.workflows.AutomationUsage
import run.ostrom.server.kernel.workflows.WorkflowRemovals
import kotlin.time.Clock
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Signs the receipt of a decision on a workflow (`decisions.ts` `recordSigned`, receipt kind `workflow`). */
interface WorkflowDecisionSigner {
    /**
     * Signs it.
     *
     * @param orgId The organization.
     * @param decision The decision (its receipt id not set yet).
     * @param action `accept`, `edit`, or `import` (a workflow created by an import).
     * @param before What the workflow was, or null for a new one.
     * @param after What it is.
     * @return The receipt id.
     */
    suspend fun sign(
        orgId: Uuid,
        decision: DecisionDoc,
        action: String,
        before: JsonObject?,
        after: JsonObject,
    ): String
}

/** Reads, saves and deletes the workflows of an organization. */
interface WorkflowsUseCase {
    /**
     * Lists the workflows.
     *
     * @param orgId The organization.
     * @param repo Only those that apply to this repository, when not null.
     * @param status Only those of this status, when not null.
     * @return The workflows.
     */
    suspend fun list(orgId: Uuid, repo: String?, status: WorkflowStatus?): List<StoredWorkflow>

    /**
     * Reads a workflow.
     *
     * @param orgId The organization.
     * @param id The workflow.
     * @return The workflow.
     */
    suspend fun get(orgId: Uuid, id: String): StoredWorkflow

    /**
     * Saves a workflow, creating it when [create] is true. A real change (nodes, edges, status, name) is a signed
     * decision; a change from a stale [expectedVersion] is refused (`workflow_conflict`). A workflow the server runs
     * (an agent, a wait or a workflow node) is checked (`invalid_workflow`, naming the node), its name must be free
     * (`workflow_name_taken`), and its calls must not loop; all that under the organization's workflow lock.
     *
     * @param orgId The organization.
     * @param raw The workflow document.
     * @param expectedVersion The version the editor read.
     * @param by Who saves.
     * @param create True for a creation (refused when the id exists); false for an update (an unknown id is not
     *   found).
     * @return The saved workflow.
     */
    suspend fun save(orgId: Uuid, raw: JsonObject, expectedVersion: Int?, by: String, create: Boolean): StoredWorkflow

    /**
     * Deletes a workflow, refused while a pinned package compiles it, another workflow calls it or an automation
     * starts it (`workflow_in_use`).
     *
     * @param orgId The organization.
     * @param id The workflow.
     */
    suspend fun delete(orgId: Uuid, id: String)

    /**
     * Checks that [raw] is a valid workflow, as [save] would, without saving it: a governance write held for the
     * console is refused at once rather than when an owner confirms it.
     *
     * @param raw The workflow document.
     *
     * @throws OstromException When it is not a valid workflow (400).
     */
    fun check(raw: JsonObject)
}

/** What an import did, or would do, with a workflow ([ImportWorkflowsUseCase]). */
enum class WorkflowImport {
    /** It did not exist: created. */
    CREATED,

    /** It differed: a new version saved. */
    UPDATED,

    /** It is already as imported. */
    UNCHANGED,
}

/** Saves the workflows another system decided (`ostrom import teamcode`). */
interface ImportWorkflowsUseCase {
    /**
     * Creates the workflow, saves a new version when [raw] differs from the stored document, or leaves it alone, so a
     * second import changes nothing. The save is [WorkflowsUseCase.save]'s, with its checks and its lock; a creation
     * is a decision `import` signed for [by], a later change an `edit` (or an `accept`) as any save.
     *
     * @param orgId The organization.
     * @param raw The workflow document.
     * @param by Who runs the import.
     * @param dryRun Checks [raw] as a save does without the organization's other workflows, and writes nothing.
     * @return What it did, or would do.
     */
    suspend fun import(orgId: Uuid, raw: JsonObject, by: String, dryRun: Boolean): WorkflowImport
}

/**
 * [WorkflowsUseCase] and [ImportWorkflowsUseCase] on the repository.
 *
 * @param repository The compiler's storage.
 * @param signer Signs the decisions.
 * @param clock The time.
 * @param automations Tells which automation starts a workflow; null, none does.
 * @param removals Told of a deleted workflow (its budgets go with it); null, nobody keeps anything about one.
 */
class WorkflowsUseCaseImpl(
    private val repository: CompilerRepository,
    private val signer: WorkflowDecisionSigner,
    private val clock: Clock,
    private val automations: AutomationUsage? = null,
    private val removals: WorkflowRemovals? = null,
) : WorkflowsUseCase, ImportWorkflowsUseCase {
    override suspend fun list(orgId: Uuid, repo: String?, status: WorkflowStatus?): List<StoredWorkflow> {
        val all = repository.workflows(orgId)
        val applies = repo?.let { r ->
            val inputs = CompileInputs(
                r, "", all.map { it.doc }, emptyList(), emptyList(), emptyList(), null, null, clock.now(),
            )
            PackageCompiler.workflowsFor(inputs).map { it.workflow.id }.toSet()
        }
        return all.filter { stored ->
            val workflow = stored.doc.workflow
            (applies == null || workflow.id in applies) && (status == null || workflow.status == status)
        }
    }

    override suspend fun get(orgId: Uuid, id: String): StoredWorkflow =
        repository.workflow(orgId, id) ?: throw OstromException(ErrorKind.NOT_FOUND, "Workflow not found.")

    override suspend fun save(
        orgId: Uuid,
        raw: JsonObject,
        expectedVersion: Int?,
        by: String,
        create: Boolean,
    ): StoredWorkflow = store(orgId, raw, expectedVersion, by, create, EDIT)

    override suspend fun import(orgId: Uuid, raw: JsonObject, by: String, dryRun: Boolean): WorkflowImport {
        val existing = repository.workflow(orgId, decode(raw).id)
        val outcome = when {
            existing == null -> WorkflowImport.CREATED
            existing.doc.raw == raw -> WorkflowImport.UNCHANGED
            else -> WorkflowImport.UPDATED
        }
        if (!dryRun && outcome != WorkflowImport.UNCHANGED) {
            store(orgId, raw, existing?.version, by, existing == null, IMPORT)
        }
        return outcome
    }

    // A save; [creation] is the decision a new workflow records (`edit`, or `import`).
    @Suppress("LongParameterList") // The save's own parameters, and the decision of a creation.
    private suspend fun store(
        orgId: Uuid,
        raw: JsonObject,
        expectedVersion: Int?,
        by: String,
        create: Boolean,
        creation: String,
    ): StoredWorkflow {
        val workflow = decode(raw)
        val repos = WorkflowRepos.of(raw)
        val author = Authorship(by, clock.now())
        // The lock holds the reads that decide (the version, the count, the names, the calls) and the write, so that
        // another save of the organization's workflows, on any replica, sees this one or waits for it.
        val (before, saved) = repository.underWorkflowLock(orgId) { locked ->
            val existing = locked.workflow(orgId, workflow.id)
            // An update names a workflow of the caller's organization: an unknown id (another organization's
            // included, which this one never sees) is not found, and never created. The write is conditional on what
            // was read: an update without a version replaces the version just read, so a deletion or a save in
            // between is a conflict, never a creation; a creation never replaces.
            val expected = when {
                create && existing != null ->
                    throw OstromException(ErrorKind.CONFLICT, "A workflow with this id exists.")
                // Every read of the workflows loads them all: a creation beyond the limit is refused.
                create && locked.workflowCount(orgId) >= MAX_WORKFLOWS -> throw OstromException(
                    ErrorKind.CONFLICT,
                    "workflow_limit: an organization keeps at most $MAX_WORKFLOWS workflows; delete one first.",
                    type = "workflow_limit",
                )
                create -> null
                existing == null -> throw OstromException(ErrorKind.NOT_FOUND, "Workflow not found.")
                else -> expectedVersion ?: existing.version
            }
            checkAmong(orgId, locked, workflow, existing?.let { it.doc.workflow.status })
            val stored = locked.saveWorkflow(orgId, workflow.id, raw, expected, author) ?: throw OstromException(
                ErrorKind.CONFLICT,
                "workflow_conflict: someone saved this workflow in between.",
                type = "workflow_conflict",
            )
            existing to stored
        }
        val change = if (before == null) creation else changeOf(before.doc, workflow, repos)
        if (change != null) record(orgId, WorkflowDoc(raw, workflow), before?.doc, change, author)
        return saved
    }

    // What a save checks against the organization's other workflows, under the lock: `rejected` only on a proposal,
    // and, for a workflow the server runs, a free name and calls that exist, never loop and cover their inputs.
    private suspend fun checkAmong(orgId: Uuid, locked: WorkflowsRepository, workflow: Workflow, was: WorkflowStatus?) {
        val rejected = workflow.status == WorkflowStatus.REJECTED
        if (rejected && was != WorkflowStatus.PROPOSED && was != WorkflowStatus.REJECTED) {
            throw InvalidWorkflowException("Invalid workflow: only a proposed workflow is rejected.")
        }
        if (!WorkflowChecks.runsOnServer(workflow)) return
        val others = locked.workflows(orgId).map { it.doc.workflow }.filter { it.id != workflow.id }
        requireFreeName(workflow, others)
        requireCalls(workflow, others)
    }

    override fun check(raw: JsonObject) {
        decode(raw)
    }

    override suspend fun delete(orgId: Uuid, id: String) {
        get(orgId, id)
        val pinned = repository.packages(orgId).filter { !it.revoked }.any { stored ->
            val pin = repository.pin(orgId, stored.envelope.repo)
            pin?.contentDigest == stored.envelope.contentDigest && compiles(stored.envelope.payload, id)
        }
        if (pinned) throw inUse("a pinned package compiles this workflow.")
        // Asked before the lock is taken: the automations keep their own storage, and a connection must not wait for
        // another while this one holds the lock. An automation that appears in between finds its workflow gone, and
        // its runs say so (`workflow_missing`).
        automations?.automationStarting(orgId, id)?.let {
            throw inUse("the automation ${it.take(MAX_ERROR)} starts it.")
        }
        repository.underWorkflowLock(orgId) { locked ->
            val callers = WorkflowChecks.callersOf(id, locked.workflows(orgId).map { it.doc.workflow })
            callers.firstOrNull()?.let { throw inUse("the workflow ${it.name.take(MAX_ERROR)} calls it.") }
            locked.deleteWorkflow(orgId, id)
        }
        removals?.removed(orgId, id)
    }

    private suspend fun record(
        orgId: Uuid,
        after: WorkflowDoc,
        before: WorkflowDoc?,
        action: String,
        author: Authorship,
    ) {
        val id = after.workflow.id
        val decision = DecisionDoc("dec_${Uuid.random()}", "workflow", id, author.by, JsText.iso(author.at), null)
        val receipt = signer.sign(orgId, decision, action, before?.let(::summary), summary(after))
        repository.addDecision(orgId, decision.copy(receiptId = receipt))
    }
}

private fun requireFreeName(workflow: Workflow, others: List<Workflow>) {
    val name = workflow.name.trim()
    if (others.none { it.name.trim().equals(name, ignoreCase = true) }) return
    throw OstromException(
        ErrorKind.CONFLICT,
        "workflow_name_taken: another workflow of the organization has this name.",
        type = "workflow_name_taken",
    )
}

private fun requireCalls(workflow: Workflow, others: List<Workflow>) = try {
    CallChecks.check(workflow, others.associateBy { it.id })
} catch (e: IllegalArgumentException) {
    throw InvalidWorkflowException("Invalid workflow: ${e.message.toString().take(MAX_ERROR)}", e)
}

private fun inUse(reason: String) =
    OstromException(ErrorKind.CONFLICT, "workflow_in_use: $reason", type = "workflow_in_use")

private fun compiles(payload: String, id: String): Boolean =
    (PackageCompiler.json.parseToJsonElement(payload).jsonObject["workflows"] as JsonArray)
        .any { (it.jsonObject["id"] as JsonPrimitive).content == id }

// The core model does not know `repos` yet (R6): it is checked here and kept in the saved JSON. A check node's
// condition is checked against the workflow's nodes and edges.
private fun decode(raw: JsonObject): Workflow = try {
    WorkflowRepos.problem(raw)?.let { throw IllegalArgumentException(it) }
    PackageCompiler.json.decodeFromJsonElement(Workflow.serializer(), WorkflowRepos.withoutField(raw))
        .also(NodeConditions::check)
        .also(WorkflowChecks::check)
} catch (e: IllegalArgumentException) {
    throw InvalidWorkflowException("Invalid workflow: ${e.message.toString().take(MAX_ERROR)}", e)
}

// What the decision says: the status, the repositories of a limited workflow, the gates and their approvers,
// and what runs.
private fun summary(doc: WorkflowDoc): JsonObject = buildJsonObject {
    val workflow = doc.workflow
    put("status", workflow.status.name.lowercase())
    doc.repos?.let { repos -> put(WorkflowRepos.FIELD, JsonArray(repos.map { JsonPrimitive(it) })) }
    put(
        "gates",
        JsonArray(
            workflow.nodes.filter { it.kind == WorkflowNodeKind.GATE }.map { node ->
                val approvers = node.approvers.orEmpty().joinToString(", ").ifEmpty { "nobody" }
                JsonPrimitive("${node.label}: $approvers")
            },
        ),
    )
    put("runs", JsonArray(workflow.nodes.mapNotNull(::runOf)))
}

private fun runOf(node: WorkflowNode): JsonElement? {
    val command = node.run?.command.orEmpty()
    val channel = node.run?.channel.orEmpty()
    val runs = if (command.isEmpty()) "" else "runs `$command`"
    val tells = if (channel.isEmpty()) "" else "tells $channel"
    return if (command.isEmpty() && channel.isEmpty()) null else JsonPrimitive("${node.label}: $runs$tells")
}

private const val MAX_ERROR = 200

private const val EDIT = "edit"
private const val IMPORT = "import"

// `accept` from proposed to accepted, `edit` otherwise; null when nothing that is decided changed.
private fun changeOf(before: WorkflowDoc, after: Workflow, repos: List<String>?): String? {
    val same = strip(before.workflow) == strip(after) && before.repos == repos
    val proposed = before.workflow.status == WorkflowStatus.PROPOSED
    val accepted = proposed && after.status == WorkflowStatus.ACCEPTED
    return when {
        same -> null
        accepted -> "accept"
        else -> EDIT
    }
}

private fun strip(workflow: Workflow) = listOf(
    workflow.nodes.map { it.copy(position = null) },
    workflow.edges,
    workflow.status,
    workflow.name,
)

// A workflow the server cannot read: 400, with the parser's reason.
private class InvalidWorkflowException(message: String, override val cause: Throwable? = null) :
    OstromException(ErrorKind.INVALID_REQUEST, message, type = "invalid_workflow")
