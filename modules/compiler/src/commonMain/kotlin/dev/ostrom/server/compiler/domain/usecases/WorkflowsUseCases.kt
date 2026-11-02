// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.domain.usecases

import dev.ostrom.core.api.models.Workflow
import dev.ostrom.core.api.models.WorkflowNode
import dev.ostrom.core.api.models.WorkflowNodeKind
import dev.ostrom.core.api.models.WorkflowStatus
import dev.ostrom.server.compiler.domain.models.Authorship
import dev.ostrom.server.compiler.domain.models.CompileInputs
import dev.ostrom.server.compiler.domain.models.DecisionDoc
import dev.ostrom.server.compiler.domain.models.StoredWorkflow
import dev.ostrom.server.compiler.domain.repositories.CompilerRepository
import dev.ostrom.server.compiler.domain.services.JsText
import dev.ostrom.server.compiler.domain.services.PackageCompiler
import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Signs the receipt of a decision on a workflow (`decisions.ts` `recordSigned`, receipt kind `workflow`). */
interface WorkflowDecisionSigner {
    /**
     * Signs it.
     *
     * @param orgId The organization.
     * @param decision The decision (its receipt id not set yet).
     * @param action `accept` or `edit`.
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
     * decision; a change from a stale [expectedVersion] is refused (`workflow_conflict`).
     *
     * @param orgId The organization.
     * @param raw The workflow document.
     * @param expectedVersion The version the editor read.
     * @param by Who saves.
     * @param create True for a creation (refused when the id exists).
     * @return The saved workflow.
     */
    suspend fun save(orgId: Uuid, raw: JsonObject, expectedVersion: Int?, by: String, create: Boolean): StoredWorkflow

    /**
     * Deletes a workflow, refused while a pinned package compiles it (`workflow_in_use`).
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

/**
 * [WorkflowsUseCase] on the repository.
 *
 * @param repository The compiler's storage.
 * @param signer Signs the decisions.
 * @param clock The time.
 */
class WorkflowsUseCaseImpl(
    private val repository: CompilerRepository,
    private val signer: WorkflowDecisionSigner,
    private val clock: Clock,
) : WorkflowsUseCase {
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
    ): StoredWorkflow {
        val workflow = decode(raw)
        val before = repository.workflow(orgId, workflow.id)
        if (create && before != null) throw OstromException(ErrorKind.CONFLICT, "A workflow with this id exists.")
        val author = Authorship(by, clock.now())
        val saved = repository.saveWorkflow(orgId, workflow.id, raw, expectedVersion, author)
            ?: throw OstromException(ErrorKind.CONFLICT, "workflow_conflict: someone saved this workflow in between.")
        val previous = if (before == null) null else before.doc.workflow
        val change = changeOf(previous, workflow)
        if (change != null) record(orgId, workflow, previous, change, author)
        return saved
    }

    override fun check(raw: JsonObject) {
        decode(raw)
    }

    override suspend fun delete(orgId: Uuid, id: String) {
        get(orgId, id)
        val pinned = repository.packages(orgId, null).filter { !it.revoked }.any { stored ->
            val pin = repository.pin(orgId, stored.envelope.repo)
            pin?.contentDigest == stored.envelope.contentDigest && compiles(stored.envelope.payload, id)
        }
        if (pinned) {
            throw OstromException(ErrorKind.CONFLICT, "workflow_in_use: a pinned package compiles this workflow.")
        }
        repository.deleteWorkflow(orgId, id)
    }

    private fun compiles(payload: String, id: String): Boolean =
        (PackageCompiler.json.parseToJsonElement(payload).jsonObject["workflows"] as JsonArray)
            .any { (it.jsonObject["id"] as JsonPrimitive).content == id }

    private fun decode(raw: JsonObject): Workflow = try {
        PackageCompiler.json.decodeFromJsonElement(Workflow.serializer(), raw)
    } catch (e: IllegalArgumentException) {
        throw InvalidWorkflowException("Invalid workflow: ${e.message.toString().take(MAX_ERROR)}", e)
    }

    private suspend fun record(
        orgId: Uuid,
        after: Workflow,
        before: Workflow?,
        action: String,
        author: Authorship,
    ) {
        val decision = DecisionDoc("dec_${Uuid.random()}", "workflow", after.id, author.by, JsText.iso(author.at), null)
        val receipt = signer.sign(orgId, decision, action, before?.let(::summary), summary(after))
        repository.addDecision(orgId, decision.copy(receiptId = receipt))
    }

    // What the decision says: the status, the gates and their approvers, and what runs.
    private fun summary(workflow: Workflow): JsonObject = buildJsonObject {
        put("status", workflow.status.name.lowercase())
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

    private companion object {
        const val MAX_ERROR = 200
    }
}

// `accept` from proposed to accepted, `edit` otherwise; null when nothing that is decided changed.
private fun changeOf(before: Workflow?, after: Workflow): String? {
    val same = before != null && strip(before) == strip(after)
    val accepted = before?.status == WorkflowStatus.PROPOSED && after.status == WorkflowStatus.ACCEPTED
    return when {
        same -> null
        accepted -> "accept"
        else -> "edit"
    }
}

private fun strip(workflow: Workflow) = listOf(
    workflow.nodes.map { it.copy(position = null) },
    workflow.edges,
    workflow.status,
    workflow.name,
)

// A workflow the server cannot read: 400, with the parser's reason.
private class InvalidWorkflowException(message: String, override val cause: Throwable) :
    OstromException(ErrorKind.INVALID_REQUEST, message)
