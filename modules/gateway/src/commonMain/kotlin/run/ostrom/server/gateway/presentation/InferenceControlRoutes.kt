// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.presentation

import run.ostrom.server.gateway.domain.ClientRequestIds
import run.ostrom.server.gateway.domain.InvalidRequestIdException
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.WriteAccess
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.presentation.requireCaller
import io.ktor.http.HttpStatusCode
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/** Who cancels an inference call: whoever may make one, an agent session's token or a person's included. */
private val CANCELLERS = WriteAccess.tokens(PrincipalKind.USER, PrincipalKind.AGENT, PrincipalKind.SERVICE)

/**
 * The routes about inference calls in flight, inside an authenticated route: the admission queue's counts, for whoever
 * may call models or read usage, and the cancellation of one of the caller's own calls (TeamCode's
 * `POST /api/v1/requests/{id}/cancel`), for whoever may call models.
 *
 * @param dependencies The use cases.
 */
fun Route.inferenceControlRoutes(dependencies: GatewayRoutesDependencies) = with(dependencies) {
    get<InferenceQueueApi> {
        val reader = call.requireCaller(
            callers,
            RelayScopes.INFERENCE_USE,
            RelayScopes.AGENT_SESSION,
            RelayScopes.INFERENCE_READ,
            RelayScopes.USAGE_READ,
        )
        val counts = getQueue(reader)
        call.respond(InferenceQueueDto(counts.waiting, counts.waitingMine))
    }
    post<InferenceRequestCancelApi> { request ->
        val caller = call.requireCaller(
            callers,
            RelayScopes.AGENT_SESSION,
            RelayScopes.INFERENCE_USE,
            writers = CANCELLERS,
        )
        cancel(caller, ClientRequestIds.parse(request.id) ?: throw InvalidRequestIdException())
        call.respond(HttpStatusCode.NoContent)
    }
}
