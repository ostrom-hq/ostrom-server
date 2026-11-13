// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations.presentation

import run.ostrom.server.automations.domain.AskWait
import run.ostrom.server.automations.domain.InvalidWaitException
import run.ostrom.server.automations.domain.AgentSessionRequiredException
import run.ostrom.server.automations.domain.MissingWaitScopeException
import run.ostrom.server.automations.domain.WaitQuery
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.auth.requireWrite
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.kernel.http.heldCalls
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.SerializationException
import kotlin.uuid.Uuid

/**
 * The waits API under `/api/v1/waits` ("Waiting for you"), the agents' MCP server at `/api/v1/waits/mcp` and the wake
 * of their sessions at `/api/v1/waits/wake`. An agent token reaches only those two, bound to one of its launch's
 * sessions; the REST routes refuse it, and the wake refuses anything else.
 *
 * @param dependencies The use cases.
 */
fun Route.waitsRoutes(dependencies: WaitsRoutesDependencies) = with(dependencies) {
    val mcp = WaitsMcpServer(dependencies)
    authenticated {
        // Held up to 30 s, as a gate's wait: counted apart from the ordinary calls.
        heldCalls {
            post<WaitsWakeApi> { params ->
                val principal = call.requirePrincipal()
                if (principal.kind != PrincipalKind.AGENT) throw MissingWaitScopeException(AGENT_SESSION)
                val session = params.session ?: throw AgentSessionRequiredException()
                val caller = agents.agentWaitCallerOf(principal, session, WAITS_READ)
                val woken = wake.wake(caller, wakeRequestOf(call.receiveText()).waitMillis)
                call.respond(WaitWakeResponse(woken.open, woken.messages))
            }
        }
        post<WaitsMcpApi> { params ->
            val callers = mcpCallers(call.requirePrincipal(), params)
            val reply = mcp.handle(call.receiveText(), params, callers)
            if (reply == null) {
                call.respond(HttpStatusCode.Accepted)
            } else {
                call.respondText(reply.toString(), ContentType.Application.Json)
            }
        }
        get<WaitsApi> { params ->
            val caller = orgResolver.waitCallerOf(call.requirePrincipal(), WAITS_READ)
            val before = params.before?.let {
                Uuid.parseOrNull(it) ?: throw InvalidWaitException("before is a wait id.")
            }
            val query = WaitQuery(
                params.mine,
                params.assignee,
                params.status,
                params.sessionId,
                params.runId,
                before,
                params.limit,
            )
            val page = read.list(caller, query)
            call.respond(WaitsResponse(page.items.map { it.toResponse() }, page.next?.toString()))
        }
        post<WaitsApi> {
            val caller = orgResolver.waitCallerOf(call.requirePrincipal(), WAITS_ASK)
            val request = call.receive<CreateWaitRequest>()
            val asked = ask.ask(
                caller,
                AskWait(request.question, request.sessionId, request.runId, request.assignee, request.kind),
                call.request.header(IDEMPOTENCY_KEY),
            )
            if (asked.replayed) call.response.header(IDEMPOTENCY_REPLAYED, "true")
            call.respond(if (asked.replayed) HttpStatusCode.OK else HttpStatusCode.Created, asked.wait.toResponse())
        }
        get<WaitsApi.Id> { params ->
            val caller = orgResolver.waitCallerOf(call.requirePrincipal(), WAITS_READ)
            call.respond(read.get(caller, waitId(params.id)).toResponse())
        }
        post<WaitsApi.Id.Answer> { params ->
            val principal = call.requirePrincipal()
            // Only a person answers, in the console or the unlocked app: never a token, whatever its scopes (S2-52).
            principal.requireWrite(WriteAccess.PERSON)
            val caller = orgResolver.waitCallerOf(principal, WAITS_WRITE)
            val request = call.receive<AnswerWaitRequest>()
            call.respond(answer.answer(caller, waitId(params.parent.id), request.action, request.values).toResponse())
        }
        post<WaitsApi.Id.Cancel> { params ->
            val caller = orgResolver.waitCallerOf(call.requirePrincipal(), WAITS_ASK)
            call.respond(answer.cancel(caller, waitId(params.parent.id)).toResponse())
        }
    }
}

/** The body of a wake: empty for the defaults. */
internal fun wakeRequestOf(body: String): WaitWakeRequest = if (body.isBlank()) {
    WaitWakeRequest()
} else {
    try {
        OstromJson.decodeFromString<WaitWakeRequest>(body)
    } catch (_: SerializationException) {
        throw InvalidWaitException("The request is malformed: {\"waitMillis\": 0 to 30000}.")
    }
}

/** What the wake needs: an agent token in its session. */
private const val AGENT_SESSION = "agent:session"
private const val IDEMPOTENCY_KEY = "Idempotency-Key"
private const val IDEMPOTENCY_REPLAYED = "Idempotency-Replayed"
