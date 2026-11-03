// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.infrastructure

import dev.ostrom.core.api.models.WaitSpec as NodeWaitSpec
import dev.ostrom.server.automations.domain.AnswerWaitUseCase
import dev.ostrom.server.automations.domain.AskWait
import dev.ostrom.server.automations.domain.AskWaitUseCase
import dev.ostrom.server.automations.domain.WaitCaller
import dev.ostrom.server.automations.domain.WaitSpec
import dev.ostrom.server.automations.domain.WaitStatus
import dev.ostrom.server.automations.domain.WaitsRepository
import dev.ostrom.server.kernel.errors.OstromException
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.launches.domain.services.LauncherResolver
import dev.ostrom.server.workflowruns.domain.services.RunWaits
import dev.ostrom.server.workflowruns.domain.services.WaitOpening
import dev.ostrom.server.workflowruns.domain.services.WaitRequest
import dev.ostrom.server.workflowruns.domain.services.WaitResult
import dev.ostrom.server.workflowruns.domain.services.WaitState
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/**
 * [RunWaits] on the automations module: a wait node's question is a wait asked by the same use case as
 * `POST /api/v1/waits` (its checks, its limits, its notifications), for the run's starter, of the member the node
 * names, with the run's id; its idempotency key is the visit (`run:<id>:<seq>`), so the question of a visit asked
 * again (after a restart) is the one already asked. The answer comes through the waits' own routes.
 *
 * @param asks Asks a wait.
 * @param answers Cancels a wait.
 * @param waits The waits.
 * @param members Resolves a login to its membership.
 */
class AutomationsRunWaits(
    private val asks: AskWaitUseCase,
    private val answers: AnswerWaitUseCase,
    private val waits: WaitsRepository,
    private val members: LauncherResolver,
) : RunWaits {
    override suspend fun ask(orgId: Uuid, request: WaitRequest): WaitOpening {
        val starter = memberOf(orgId, request.starter)
        val named = request.assignee
        val assignee = named?.let { memberOf(orgId, it) }
        val question = question(request)
        // A refusal of the question (its stable message) fails the visit; anything else is retried.
        return try {
            when {
                starter == null -> WaitOpening(refusal = STARTER_GONE)
                named != null && assignee == null -> WaitOpening(refusal = "$named is not a member of the organization")
                question == null -> WaitOpening(refusal = MALFORMED)
                else -> {
                    val ask = AskWait(question, runId = request.runId, assignee = assignee)
                    val key = "run:${request.runId}:${request.seq}"
                    WaitOpening(waitId = asks.ask(caller(orgId, starter), ask, key).wait.id)
                }
            }
        } catch (refused: OstromException) {
            WaitOpening(refusal = refused.message)
        }
    }

    override suspend fun result(orgId: Uuid, waitId: Uuid): WaitResult? = waits.get(orgId, waitId)?.let { wait ->
        val answer = wait.answer
        when {
            wait.status == WaitStatus.CANCELLED -> WaitResult(WaitState.CANCELLED)
            answer == null -> WaitResult(WaitState.OPEN)
            else -> WaitResult(WaitState.ANSWERED, answer.action, JsonObject(answer.values).toString())
        }
    }

    override suspend fun cancel(orgId: Uuid, starter: String, waitId: Uuid) {
        val member = memberOf(orgId, starter) ?: return
        try {
            answers.cancel(caller(orgId, member), waitId)
        } catch (ignored: OstromException) {
            // Answered or withdrawn meanwhile: it is left as it is.
        }
    }

    // The membership id of a login, or null when it is not a member any more.
    private suspend fun memberOf(orgId: Uuid, login: String): String? =
        members.colleague(orgId, login)?.let { members.member(orgId, it.userId) }?.memberId?.toString()

    // The run's starter, as a member who neither signed in nor administers: the waits apply their own rules to it.
    private fun caller(orgId: Uuid, memberId: String) = WaitCaller(orgId, memberId, admin = false, interactive = false)

    // The node's question as the waits take it, or null when it names a field type they do not know.
    private fun question(request: WaitRequest): WaitSpec? = try {
        val node = OstromJson.encodeToString(NodeWaitSpec.serializer(), request.question)
        OstromJson.decodeFromString(WaitSpec.serializer(), node)
    } catch (ignored: SerializationException) {
        null
    }
}

// Why a wait is not asked: the run's starter left the organization, or the waits cannot read the question.
private const val STARTER_GONE = "The person who started the run is no longer a member of the organization."
private const val MALFORMED = "The wait node's question is malformed: a field type the waits do not know."
