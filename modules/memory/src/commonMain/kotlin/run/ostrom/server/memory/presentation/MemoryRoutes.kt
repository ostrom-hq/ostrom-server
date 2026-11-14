// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.presentation

import run.ostrom.core.api.models.CreateMemoryRequest
import run.ostrom.core.api.models.MemoryApi
import run.ostrom.core.api.models.MemoryProposals
import run.ostrom.core.api.models.MemorySearchResult
import run.ostrom.core.api.models.UpdateMemoryRequest
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.memory.domain.InvalidMemoryException
import run.ostrom.server.memory.domain.MemoryCaller
import run.ostrom.server.memory.domain.MemoryRules
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The memory's routes (`memory.v1`, TeamCode's `MemoryRoutes`): a person, or a credential holding `memory:use`
 * ([MemoryCallers]). Every rule is in the service.
 *
 * @param dependencies The service and the callers.
 */
fun Route.memoryRoutes(dependencies: MemoryRoutesDependencies) = with(dependencies) {
    authenticated {
        get<MemoryResource.Index> { params ->
            val page = memory.index(
                call.caller(callers),
                scopeOf(params.scope),
                tagsOf(params.tags),
                cursorOf(params.after),
                limitOf(params.limit, MemoryRules.PAGE_LIMIT),
            )
            call.respond(page.toDto())
        }
        get<MemoryResource.Search> { params ->
            val query = params.q ?: throw InvalidMemoryException("Send the words to search for as q.")
            val found = memory.search(
                call.caller(callers),
                query,
                tagsOf(params.tags),
                limitOf(params.limit, MemoryRules.SEARCH_LIMIT),
            )
            call.respond(MemorySearchResult(found.map { it.toDto() }))
        }
        post<MemoryResource> {
            val caller = call.caller(callers)
            val request = call.receive<CreateMemoryRequest>()
            val created = memory.create(caller, request.toDomain(), request.sessionId?.take(MAX_SESSION_ID))
            call.respond(HttpStatusCode.Created, created.toDto())
        }
        get<MemoryResource.Proposals> {
            val caller = call.caller(callers)
            call.respond(MemoryProposals(proposals.proposals(caller).map { it.toDto() }))
        }
        post<MemoryResource.Proposals.Accept> { params ->
            val caller = call.caller(callers)
            call.respond(proposals.decide(caller, entryId(params.id), accept = true).toDto())
        }
        post<MemoryResource.Proposals.Reject> { params ->
            val caller = call.caller(callers)
            call.respond(proposals.decide(caller, entryId(params.id), accept = false).toDto())
        }
        get<MemoryResource.Entry> { params ->
            val caller = call.caller(callers)
            call.respond(memory.read(caller, entryId(params.id)).toDto())
        }
        patch<MemoryResource.Entry> { params ->
            val caller = call.caller(callers)
            val id = entryId(params.id)
            val request = call.receive<UpdateMemoryRequest>()
            call.respond(memory.update(caller, id, request.toDomain()).toDto())
        }
        delete<MemoryResource.Entry> { params ->
            val caller = call.caller(callers)
            memory.delete(caller, entryId(params.id))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private suspend fun ApplicationCall.caller(callers: MemoryCallers): MemoryCaller =
    callers.of(requirePrincipal(), request.headers[MemoryApi.ORIGIN_HEADER])

/** A session id longer than any Ostrom session's is cut, never stored whole. */
private const val MAX_SESSION_ID = 64
