// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.presentation

import run.ostrom.core.api.models.MemoryReviews
import run.ostrom.core.api.models.SetMemoryCurationRequest
import run.ostrom.core.api.models.SupersededMemories
import run.ostrom.server.kernel.auth.authenticated
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The memory's curation routes (TeamCode #273): the setting, an administrator's; the reviews, the superseded entries
 * and their restore, a person's ([MemoryCallers] resolves who; every rule is in the service).
 *
 * @param dependencies The curation and the callers.
 */
fun Route.memoryCurationRoutes(dependencies: MemoryRoutesDependencies) = with(dependencies) {
    authenticated {
        get<MemoryResource.Curation> {
            call.respond(curation.settings(call.caller(callers)).toDto())
        }
        put<MemoryResource.Curation> {
            val caller = call.caller(callers)
            val request = call.receive<SetMemoryCurationRequest>()
            call.respond(curation.setEnabled(caller, request.enabled).toDto())
        }
        get<MemoryResource.Reviews> {
            val caller = call.caller(callers)
            call.respond(MemoryReviews(curation.reviews(caller).map { it.toDto() }))
        }
        post<MemoryResource.Reviews.Apply> { params ->
            val caller = call.caller(callers)
            call.respond(curation.resolve(caller, reviewId(params.id), apply = true).toDto())
        }
        post<MemoryResource.Reviews.Dismiss> { params ->
            val caller = call.caller(callers)
            call.respond(curation.resolve(caller, reviewId(params.id), apply = false).toDto())
        }
        get<MemoryResource.Superseded> {
            val caller = call.caller(callers)
            call.respond(SupersededMemories(curation.superseded(caller).map { it.toDto() }))
        }
        post<MemoryResource.Entry.Restore> { params ->
            val caller = call.caller(callers)
            call.respond(curation.restore(caller, entryId(params.parent.id)).toDto())
        }
    }
}
