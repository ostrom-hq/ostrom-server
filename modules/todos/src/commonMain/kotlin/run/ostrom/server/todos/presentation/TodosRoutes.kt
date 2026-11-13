// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos.presentation

import run.ostrom.core.api.models.AddTodoItemRequest
import run.ostrom.core.api.models.CompleteTodoItemRequest
import run.ostrom.core.api.models.CreateTodoRequest
import run.ostrom.core.api.models.DropTodoItemRequest
import run.ostrom.core.api.models.LinkTodoItemRequest
import run.ostrom.core.api.models.NoteTodoItemRequest
import run.ostrom.core.api.models.ResetTodoRequest
import run.ostrom.core.api.models.SessionTodo
import run.ostrom.core.api.models.StartTodoItemRequest
import run.ostrom.core.api.models.StartTodoItemResponse
import run.ostrom.core.api.models.TodoContext
import run.ostrom.core.api.models.TodoHookErrorRequest
import run.ostrom.core.api.models.TodoStopHook
import run.ostrom.core.api.models.TodoStopHookRequest
import run.ostrom.core.api.models.TodoStopHooks
import run.ostrom.core.api.models.TodoStopRequest
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.auditDetails
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.todos.domain.TodoList
import run.ostrom.server.todos.domain.TodoStopInput
import run.ostrom.server.todos.domain.TodoStopPhase
import run.ostrom.server.todos.domain.invalidTodo
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The session todo routes (`todos.v1`): a session's agent token and its person change it, an administrator reads it,
 * and an administrator who signed in turns a member's Stop hook on or off. Every rule is in the use cases.
 *
 * @param dependencies The use cases and callers.
 */
fun Route.todosRoutes(dependencies: TodosRoutesDependencies) {
    authenticated {
        sessionTodoRoutes(dependencies)
        todoItemRoutes(dependencies)
        stopHookRoutes(dependencies)
    }
}

// The list, its reminder block and the Stop hook's questions.
private fun Route.sessionTodoRoutes(dependencies: TodosRoutesDependencies) = with(dependencies) {
    get<TodoSessionApi> { params ->
        val caller = callers.reader(call.requirePrincipal(), params.sessionId)
        call.respondTodo(read.get(caller, params.sessionId))
    }
    post<TodoSessionApi> {
        val request = call.receive<CreateTodoRequest>()
        val caller = callers.writer(call.requirePrincipal(), request.sessionId)
        val items = request.items.map { it.toDomain() }
        val list = change.create(caller, request.sessionId, request.reference, request.refs.toRefs(), items)
        call.respondTodo(list, HttpStatusCode.Created)
    }
    get<TodoSessionApi.Context> { params ->
        val sessionId = params.parent.sessionId
        val text = read.context(callers.reader(call.requirePrincipal(), sessionId), sessionId)
        call.respond(OstromJson.encodeToJsonElement(TodoContextAnswer, TodoContext(text)))
    }
    post<TodoSessionApi.Stop> {
        val request = call.receive<TodoStopRequest>()
        val caller = callers.writer(call.requirePrincipal(), request.sessionId)
        call.respond(stop(caller, request.sessionId, request.toInput()).toDto())
    }
    post<TodoSessionApi.HookError> {
        val request = call.receive<TodoHookErrorRequest>()
        val caller = callers.writer(call.requirePrincipal(), request.sessionId)
        call.respondTodo(change.hookError(caller, request.sessionId, request.text))
    }
    post<TodoSessionApi.Reset> {
        val request = call.receive<ResetTodoRequest>()
        val principal = call.requirePrincipal()
        val cleared = change.reset(callers.writer(principal, request.sessionId), request.sessionId)
        // What was cleared, never its content.
        val details = auditDetails("list" to cleared.id, "status" to cleared.status.wire, "items" to cleared.items.size)
        audit.record(OrgAuditRecord(cleared.orgId, principal, "todo.reset", "session", cleared.sessionId, details))
        call.respondTodo(null)
    }
}

// An item: added on the way, started, noted, proven, linked, or its drop asked.
private fun Route.todoItemRoutes(dependencies: TodosRoutesDependencies) = with(dependencies) {
    post<TodoSessionApi.Items> {
        val request = call.receive<AddTodoItemRequest>()
        val caller = callers.writer(call.requirePrincipal(), request.sessionId)
        call.respondTodo(change.add(caller, request.sessionId, request.item.toDomain(), request.reason))
    }
    post<TodoSessionApi.Items.Item.Start> { params ->
        val request = call.receive<StartTodoItemRequest>()
        val caller = callers.writer(call.requirePrincipal(), request.sessionId)
        val started = change.start(caller, request.sessionId, params.parent.item, request.background, request.agent)
        call.respond(StartTodoItemResponse(started.list.toDto(), started.marker))
    }
    post<TodoSessionApi.Items.Item.Notes> { params ->
        val request = call.receive<NoteTodoItemRequest>()
        val caller = callers.writer(call.requirePrincipal(), request.sessionId)
        call.respondTodo(change.note(caller, request.sessionId, params.parent.item, request.text))
    }
    post<TodoSessionApi.Items.Item.Proof> { params ->
        val request = call.receive<CompleteTodoItemRequest>()
        val caller = callers.writer(call.requirePrincipal(), request.sessionId)
        val reports = request.checks.map { it.toDomain() }
        call.respondTodo(change.complete(caller, request.sessionId, params.parent.item, reports))
    }
    post<TodoSessionApi.Items.Item.Link> { params ->
        val request = call.receive<LinkTodoItemRequest>()
        val caller = callers.writer(call.requirePrincipal(), request.sessionId)
        val link = link(request.kind, request.ref, request.number, request.sha, request.tag)
        call.respondTodo(change.link(caller, request.sessionId, params.parent.item, link, request.replaceClosed))
    }
    post<TodoSessionApi.Items.Item.Drop> { params ->
        val request = call.receive<DropTodoItemRequest>()
        val caller = callers.writer(call.requirePrincipal(), request.sessionId)
        call.respondTodo(change.requestDrop(caller, request.sessionId, params.parent.item, request.reason))
    }
}

// The per-member switch of the Stop hook, in the console.
private fun Route.stopHookRoutes(dependencies: TodosRoutesDependencies) = with(dependencies) {
    get<TodoStopHookApi> {
        val disabled = settings.disabled(callers.console(call.requirePrincipal()))
        call.respond(TodoStopHooks(disabled.sorted().map { TodoStopHook(it, enabled = false) }))
    }
    put<TodoStopHookApi.Member> { params ->
        val caller = callers.console(call.requirePrincipal())
        val request = call.receive<TodoStopHookRequest>()
        settings.set(caller, params.memberId, request.enabled)
        call.respond(TodoStopHook(params.memberId, request.enabled))
    }
}

private suspend fun ApplicationCall.respondTodo(list: TodoList?, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(status, OstromJson.encodeToJsonElement(SessionTodoAnswer, SessionTodo(list?.toDto())))

/**
 * The domain's form of one question of the Stop hook.
 *
 * @throws run.ostrom.server.todos.domain.TodoException `invalid_stop` for a phase this server does not know.
 */
internal fun TodoStopRequest.toInput(): TodoStopInput {
    val phase = TodoStopPhase.fromWire(phase.value)
        ?: throw invalidTodo("invalid_stop", "phase is begin, verify or settle.")
    return TodoStopInput(
        phase = phase,
        background = background,
        items = items.map { it.toDomain() },
        orphans = orphans,
        lastMessage = lastMessage?.take(MAX_LAST_MESSAGE),
    )
}

/** The longest last message of the agent kept for the owner (the contract's bound). */
private const val MAX_LAST_MESSAGE = 2_000
