// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.presentation

import run.ostrom.server.compiler.domain.usecases.WorkflowsUseCase
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.kernel.auth.GovernanceChange
import run.ostrom.server.kernel.auth.GovernanceConfirmer
import run.ostrom.server.kernel.auth.GovernancePreview
import run.ostrom.server.kernel.auth.GovernanceScopes
import run.ostrom.server.kernel.auth.GovernanceWrite
import run.ostrom.server.kernel.auth.holdForConfirmation
import run.ostrom.server.kernel.http.OstromJson
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.Uuid

/**
 * The governance writes of the workflows, held for a confirmation when a token asks for a decision (audit CLI H1): a
 * workflow created accepted, an edit that is not a proposal, a deletion. A proposal is never held.
 *
 * @param actors Names the confirmer as the gates know them.
 * @param workflows The workflows.
 */
class CompilerGovernanceChange(
    private val actors: GateActorResolver,
    private val workflows: WorkflowsUseCase,
) : GovernanceChange {
    override val kinds: Set<String> = WorkflowWrites.ALL

    override suspend fun preview(orgId: Uuid, write: GovernanceWrite): GovernancePreview {
        val payload = write.payload
        return when (write.kind) {
            WorkflowWrites.CREATE -> {
                val document = payload.body().jsonObject
                workflows.check(document)
                GovernancePreview("Create the workflow ${document.named()}", null, document)
            }
            WorkflowWrites.SAVE -> {
                val request = payload.save()
                workflows.check(request.workflow)
                val before = documentOf(workflows.get(orgId, payload.id()))
                GovernancePreview("Edit the workflow ${before.named()}", before, request.workflow)
            }
            else -> {
                val before = documentOf(workflows.get(orgId, payload.id()))
                GovernancePreview("Delete the workflow ${before.named()}", before, null)
            }
        }
    }

    override suspend fun apply(orgId: Uuid, write: GovernanceWrite, confirmer: GovernanceConfirmer): JsonElement? {
        // Signed by the gates' name of the confirmer, else by their membership.
        val actor = actors.resolve(confirmer.principal)
        val by = if (actor != null) actor.id else confirmer.member.memberId.toString()
        val payload = write.payload
        return when (write.kind) {
            WorkflowWrites.CREATE ->
                documentOf(workflows.save(orgId, payload.body().jsonObject, null, by, create = true))
            WorkflowWrites.SAVE -> {
                val request = payload.save()
                documentOf(workflows.save(orgId, request.workflow, request.expectedVersion, by, create = false))
            }
            else -> {
                workflows.delete(orgId, payload.id())
                null
            }
        }
    }

    // A checked workflow, or a saved one: it always has its id.
    private fun JsonObject.named(): String = getValue("id").jsonPrimitive.content

    /** The payloads of the workflows' governance writes. */
    companion object {
        private const val ID = "id"
        private const val BODY = "body"

        /**
         * The payload of a write.
         *
         * @param id The workflow, as the path names it.
         * @param body The request's body, if any.
         *
         * @return The payload.
         */
        fun payload(id: String?, body: JsonElement?): JsonObject = buildJsonObject {
            id?.let { put(ID, JsonPrimitive(it)) }
            body?.let { put(BODY, it) }
        }

        private fun JsonObject.id(): String = getValue(ID).jsonPrimitive.content

        private fun JsonObject.body(): JsonElement = getValue(BODY)

        private fun JsonObject.save(): SaveWorkflowRequest =
            OstromJson.decodeFromJsonElement(SaveWorkflowRequest.serializer(), body())
    }
}

/** The kinds of the workflows' governance writes (contract `GovernanceChangeKind`). */
object WorkflowWrites {
    /** `POST /workflows` with a workflow that is not proposed. */
    const val CREATE: String = "workflows.create"

    /** `PUT /workflows/{id}` that is not a proposal. */
    const val SAVE: String = "workflows.save"

    /** `DELETE /workflows/{id}`. */
    const val DELETE: String = "workflows.delete"

    /** All of them. */
    val ALL: Set<String> = setOf(CREATE, SAVE, DELETE)
}

// A token's decision on a workflow, held for the console: nothing is written.
internal suspend fun ApplicationCall.hold(actor: GateActor, kind: String, payload: JsonObject) =
    holdForConfirmation(actor.orgId, GovernanceScopes.HARNESS_WRITE, GovernanceWrite(kind, payload))
