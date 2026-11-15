// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.kernel.http.bodyLimit
import run.ostrom.server.kernel.text.TranscriptSanitizer
import run.ostrom.server.runners.domain.CloudConversationRules
import run.ostrom.server.runners.domain.CloudEventType
import run.ostrom.server.runners.domain.CloudInputStatus
import run.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import run.ostrom.server.runners.domain.RunnerAccepts
import run.ostrom.server.runners.domain.RunnerAgentConfig
import run.ostrom.server.runners.domain.RunnerEvent
import run.ostrom.server.runners.domain.RunnerExecution
import run.ostrom.server.runners.domain.RunnerInputAck
import run.ostrom.server.runners.domain.TurnMarker
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.uuid.Uuid

/**
 * The agent channel of the runner (`runners.cloud.conversation.v1`): the credential first, the body after (S2-46),
 * then the execution's fence; an unknown credential and another execution are both `409 execution_fenced`.
 * `GET /api/v1/cloud/runner/files/{id}` stays unserved: this server stores no cloud files yet.
 */
fun Route.runnerConversationRoutes(dependencies: RunnerConversationRoutesDependencies) = with(dependencies) {
    bodyLimit(SMALL_BODY_LIMIT) {
        post<RunnerAgentApi> {
            val token = checked(call)
            val request = call.receive<RunnerAgentRequest>()
            val agent = runner.agent(execution(token, request.sessionId, request.epoch))
            call.respond(agent.toResponse())
        }
        post<RunnerInputsApi> {
            val token = checked(call)
            val request = call.receive<RunnerInputsRequest>()
            val fenced = execution(token, request.sessionId, request.epoch)
            val accepts = RunnerAccepts(
                taskStops = request.acceptsTaskStops,
                modelChanges = request.acceptsModelChanges,
                effortChanges = request.acceptsEffortChanges,
            )
            val polled = runner.poll(fenced, request.waitMillis, request.desiredStatus, accepts)
            val inputs = polled.inputs.map { RunnerInputDto(it.id.toString(), it.kind.wire, it.text, it.attempts) }
            call.respond(RunnerInputsResponse(inputs, polled.desiredState.wire))
        }
    }
    bodyLimit(EVENTS_BODY_LIMIT) {
        post<RunnerEventsApi> {
            val token = checked(call)
            val request = call.receive<RunnerEventsRequest>()
            val fenced = execution(token, request.sessionId, request.epoch)
            val ack = runner.events(fenced, request.events.map { it.toDomain() })
            call.respond(RunnerEventsResponse(ack.acceptedThrough, ack.lastSeq))
        }
    }
    bodyLimit(ACK_BODY_LIMIT) {
        post<RunnerInputsApi.Ack> {
            val token = checked(call)
            val request = call.receive<RunnerInputsAckRequest>()
            val fenced = execution(token, request.sessionId, request.epoch)
            runner.acknowledge(fenced, request.acks.map { it.toDomain() })
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** The execution a call names, once its credential is the one of that execution. */
private suspend fun RunnerConversationRoutesDependencies.execution(token: String, sessionId: String, epoch: Long) =
    authenticate(token, parseId(sessionId), epoch).let { RunnerExecution(it.orgId, it.id, it.epoch) }

/** The credential of a call, known to a resolver before the body is read. */
private suspend fun RunnerConversationRoutesDependencies.checked(call: ApplicationCall): String {
    val token = call.runnerToken()
    authenticate.resolve(token)
    return token
}

/** An event as stored: bounded, masked and stripped of reasoning, with what its `turn` says. */
internal fun RunnerEventDto.toDomain(): RunnerEvent {
    val parsed = CloudEventType.parse(type)?.takeIf { it != CloudEventType.INPUT }
        ?: throw CloudConversationRules.invalid("invalid_events", "Unknown event type.")
    return RunnerEvent(sequence, parsed, TranscriptSanitizer.encode(data), turnOf(parsed, data))
}

/** The agent's configuration as the runner reads it. */
internal fun RunnerAgentConfig.toResponse() = RunnerAgentResponse(
    kind = kind.wire,
    model = model,
    effort = effort,
    conversation = conversation.wire,
    permissionRelay = permissionRelay,
    permissionDeadlineMinutes = permissionDeadlineMinutes,
    liveText = liveText,
    backgroundTasks = backgroundTasks,
    owner = owner?.let { RunnerOwnerDto(it.username, it.githubLogin) },
)

/** What a `turn` event says: a turn started, or ended. */
internal fun turnOf(type: CloudEventType, data: JsonObject): TurnMarker? {
    val phase = (data["phase"] as? JsonPrimitive)?.contentOrNull
    return when {
        type != CloudEventType.TURN -> null
        phase == "started" -> TurnMarker.STARTED
        phase in CloudConversationRules.TURN_END_PHASES -> TurnMarker.ENDED
        else -> null
    }
}

private fun RunnerInputAckDto.toDomain(): RunnerInputAck {
    val consumed = when (status) {
        CloudInputStatus.CONSUMED.wire -> true
        CloudInputStatus.REJECTED.wire -> false
        else -> throw CloudConversationRules.invalid("invalid_acks", "Acknowledge as consumed or rejected.")
    }
    val input = Uuid.parseOrNull(id) ?: throw CloudConversationRules.invalid("invalid_acks", "Malformed input id.")
    return RunnerInputAck(input, consumed, reason)
}

private fun parseId(raw: String): Uuid =
    Uuid.parseOrNull(raw) ?: throw InvalidCloudSessionRequestException("Malformed session id.")

/** An agent or inputs call is a few fields. */
private const val SMALL_BODY_LIMIT = 4L * 1024

/** A batch of events, bounded as in TeamCode and the contract: 2 MB whatever the count (each is cut to 60 KB). */
private const val EVENTS_BODY_LIMIT = 2L * 1024 * 1024

/** At most 100 acknowledgements. */
private const val ACK_BODY_LIMIT = 64L * 1024
