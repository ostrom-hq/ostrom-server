// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.presentation

import dev.ostrom.server.automations.domain.AskWait
import dev.ostrom.server.automations.domain.InvalidWaitException
import dev.ostrom.server.automations.domain.WaitQuery
import dev.ostrom.server.kernel.auth.WriteAccess
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.auth.requirePrincipal
import dev.ostrom.server.kernel.auth.requireWrite
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
import kotlin.uuid.Uuid

/**
 * The waits API under `/api/v1/waits` ("Waiting for you"), and the agents' MCP server at `/api/v1/waits/mcp`. An
 * agent token reaches only the MCP server, bound to one of its launch's sessions; the REST routes refuse it.
 *
 * @param dependencies The use cases.
 */
fun Route.waitsRoutes(dependencies: WaitsRoutesDependencies) = with(dependencies) {
    val mcp = WaitsMcpServer(dependencies)
    authenticated {
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

private const val IDEMPOTENCY_KEY = "Idempotency-Key"
private const val IDEMPOTENCY_REPLAYED = "Idempotency-Replayed"
