// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.http.bodyLimit
import run.ostrom.server.runners.domain.CloudConversationRules
import run.ostrom.server.runners.domain.CloudSessionNotFoundException
import run.ostrom.server.runners.domain.InputOutcome
import run.ostrom.server.runners.domain.InputRequest
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.uuid.Uuid

/**
 * The conversation routes of a cloud session (TeamCode's `CloudConversationRoutes`, members' side): the transcript
 * (`events`, `events/stream`, `agents`), the inputs (`messages`, `interrupt`, `permissions`, `tasks/stop`) and the
 * agent's options (`permission-bypass`, `model`, `effort`). The `files` routes are not served yet.
 *
 * @param dependencies The service.
 */
fun Route.cloudConversationRoutes(dependencies: CloudConversationRoutesDependencies) {
    authenticated {
        transcriptRoutes(dependencies)
        inputRoutes(dependencies)
        optionRoutes(dependencies)
    }
}

/** The transcript: its pages, its stream and its subagents. */
private fun Route.transcriptRoutes(dependencies: CloudConversationRoutesDependencies) = with(dependencies) {
    get<CloudEventsApi> { params ->
        val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_READ)
        val page = conversation.events(
            caller,
            sessionId(params.parent.id),
            cursor(params.after),
            cursor(params.before),
            params.limit ?: DEFAULT_EVENT_PAGE,
            params.feed,
        )
        call.respond(page.toDto())
    }
    get<CloudEventsApi.Stream> { params ->
        val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_READ)
        val id = sessionId(params.parent.parent.id)
        val after = cursor(call.request.headers[LAST_EVENT_ID] ?: params.after) ?: 0L
        // Refused before the answer is committed, so a refusal is a plain error.
        conversation.openStream(caller, id, after)
        call.stream { send -> conversation.frames(caller, id, after, send) }
    }
    get<CloudAgentsApi> { params ->
        val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_READ)
        val agents = conversation.subagents(caller, sessionId(params.parent.id))
        call.respond(CloudSubagentListDto(agents.map { it.toDto() }))
    }
}

/** The inputs: messages, interrupts, permission answers and task stops. */
private fun Route.inputRoutes(dependencies: CloudConversationRoutesDependencies) = with(dependencies) {
    get<CloudMessagesApi> { params ->
        val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_READ)
        val before = params.before?.let { Uuid.parseOrNull(it) ?: throw badCursor() }
        val page = conversation.inputs(
            caller,
            sessionId(params.parent.id),
            before,
            params.limit ?: DEFAULT_INPUT_PAGE,
        )
        call.respond(CloudInputPageDto(page.inputs.map { it.toDto() }, page.nextBefore?.toString()))
    }
    get<CloudMessagesApi.Input> { params ->
        val caller = orgResolver.callerOf(call.requirePrincipal(), CLOUD_READ)
        val inputId = Uuid.parseOrNull(params.input) ?: throw CloudSessionNotFoundException()
        call.respond(conversation.input(caller, sessionId(params.parent.parent.id), inputId).toDto())
    }
    post<CloudInterruptApi> { params ->
        val principal = call.requirePrincipal()
        val caller = orgResolver.callerOf(principal, CLOUD_WRITE)
        val request = InputRequest(null, false, null, null, null)
        call.accepted(conversation.interrupt(caller, principal, sessionId(params.parent.id), request))
    }
    bodyLimit(MESSAGE_BODY_LIMIT) {
        post<CloudMessagesApi> { params ->
            val principal = call.requirePrincipal()
            val caller = orgResolver.callerOf(principal, CLOUD_WRITE)
            val body = call.receive<SendCloudMessageBody>()
            if (body.fileIds.isNotEmpty()) {
                throw CloudConversationRules.invalid("files_unsupported", "This server does not take files yet.")
            }
            val key = call.clientKey(body.clientMessageId)
            val digest = digestOf("message", body.text.orEmpty(), body.wake.toString())
            val request = InputRequest(body.text, body.wake, key, digest, body.expectedLastEventSeq)
            call.accepted(conversation.send(caller, principal, sessionId(params.parent.id), request))
        }
    }
    bodyLimit(OPTION_BODY_LIMIT) {
        post<CloudPermissionsApi> { params ->
            val principal = call.requirePrincipal()
            val caller = orgResolver.callerOf(principal, CLOUD_WRITE)
            val answer = call.receive<AnswerCloudPermissionBody>().toDomain()
            val text = answerText(answer)
            val request = InputRequest(text, false, call.clientKey(null), digestOf("permission", text), null)
            call.accepted(conversation.answer(caller, principal, sessionId(params.parent.id), answer, request))
        }
        post<CloudStopTaskApi> { params ->
            val principal = call.requirePrincipal()
            val caller = orgResolver.callerOf(principal, CLOUD_WRITE)
            val taskId = call.receive<StopCloudTaskBody>().taskId
            val request = InputRequest(taskId, false, null, null, null)
            call.accepted(conversation.stopTask(caller, principal, sessionId(params.parent.id), taskId, request))
        }
    }
}

/** The agent's options: the owner's Bypass, the model and the effort. */
private fun Route.optionRoutes(dependencies: CloudConversationRoutesDependencies) = with(dependencies) {
    bodyLimit(OPTION_BODY_LIMIT) {
        put<CloudPermissionBypassApi> { params ->
            val principal = call.requirePrincipal()
            val caller = orgResolver.callerOf(principal, CLOUD_WRITE)
            val enabled = call.receive<SetCloudPermissionBypassBody>().enabled
            val session = conversation.setPermissionBypass(caller, principal, sessionId(params.parent.id), enabled)
            call.respond(session.toResponse())
        }
        put<CloudModelApi> { params ->
            val principal = call.requirePrincipal()
            val caller = orgResolver.callerOf(principal, CLOUD_WRITE)
            val model = call.receive<SetCloudModelBody>().model
            val session = conversation.setAgentOption(caller, principal, sessionId(params.parent.id), false, model)
            call.respond(session.toResponse())
        }
        put<CloudEffortApi> { params ->
            val principal = call.requirePrincipal()
            val caller = orgResolver.callerOf(principal, CLOUD_WRITE)
            val effort = call.receive<SetCloudEffortBody>().effort
            val session = conversation.setAgentOption(caller, principal, sessionId(params.parent.id), true, effort)
            call.respond(session.toResponse())
        }
    }
}

/** The header an `EventSource` resumes with. */
private const val LAST_EVENT_ID = "Last-Event-ID"
private const val IDEMPOTENCY_KEY = "Idempotency-Key"
private const val IDEMPOTENCY_REPLAYED = "Idempotency-Replayed"
private const val MAX_KEY_CHARS = 200
private const val DEFAULT_EVENT_PAGE = 100
private const val DEFAULT_INPUT_PAGE = 50

/** A message: 32 000 characters, escaped, with room to spare (TeamCode's 256 KiB). */
private const val MESSAGE_BODY_LIMIT = 256L * 1024

/** A permission answer or an option (TeamCode's 64 KiB). */
private const val OPTION_BODY_LIMIT = 64L * 1024

private val KEY = Regex("[\\x21-\\x7E]{1,$MAX_KEY_CHARS}")

private fun sessionId(raw: String): Uuid = Uuid.parseOrNull(raw) ?: throw CloudSessionNotFoundException()

private fun badCursor() = CloudConversationRules.invalid("invalid_cursor", "Use a valid cursor.")

private fun cursor(raw: String?): Long? = raw?.let { it.toLongOrNull() ?: throw badCursor() }

/** The idempotency key: the header, or the body's alias, never both (TeamCode's rule). */
private fun ApplicationCall.clientKey(alias: String?): String? {
    val header = request.headers[IDEMPOTENCY_KEY]
    if (header != null && alias != null) {
        throw CloudConversationRules.invalid(
            "invalid_idempotency_key",
            "Send Idempotency-Key or clientMessageId, not both.",
        )
    }
    val key = header ?: alias ?: return null
    if (!KEY.matches(key)) {
        throw CloudConversationRules.invalid(
            "invalid_idempotency_key",
            "Idempotency-Key is 1 to $MAX_KEY_CHARS visible characters.",
        )
    }
    return key
}

/** `202` with the input; a replay says so (`Idempotency-Replayed: true`). */
private suspend fun ApplicationCall.accepted(outcome: InputOutcome) {
    if (outcome is InputOutcome.Replayed) response.header(IDEMPOTENCY_REPLAYED, "true")
    respond(HttpStatusCode.Accepted, outcome.input.toDto())
}

/** What an idempotency key is bound to: a retry must send the same. */
private fun digestOf(vararg parts: String): String {
    val joined = parts.joinToString("\u0000")
    return HASHER.hashBlocking(joined.encodeToByteArray()).toHexString()
}

private val HASHER = CryptographyProvider.Default.get(SHA256).hasher()
