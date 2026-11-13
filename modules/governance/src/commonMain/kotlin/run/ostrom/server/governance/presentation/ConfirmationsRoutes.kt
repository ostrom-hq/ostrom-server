// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.governance.presentation

import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.http.heldCalls
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlin.time.Duration.Companion.seconds

/**
 * The confirmations routes of the contract. The requester reads theirs with any of their credentials (the CLI waits
 * there); an owner or an admin reads and answers them from the console, a browser session only. The organization is
 * always the caller's (the `Ostrom-Org` header, else their only one): another organization's confirmation is a 404.
 *
 * @param dependencies The use cases.
 */
fun Route.confirmationsRoutes(dependencies: ConfirmationsRoutesDependencies) = with(dependencies) {
    // Waits up to 30 s for the answer: a held call, counted apart from the server's other calls from before
    // authentication on (P1 memory).
    heldCalls {
        authenticated {
            get<ConfirmationApi> { params ->
                val wait = (params.wait ?: 0).coerceIn(0, MAX_WAIT_SECONDS).seconds
                val confirmation = get(call.requirePrincipal(), params.id, wait)
                call.respond(confirmation.toDto(people, clock.now()))
            }
        }
    }
    authenticated {
        post<ConfirmationApi.Answer> { params ->
            val principal = call.requirePrincipal()
            val body = call.receive<AnswerConfirmationRequest>()
            val answered = answer(principal, params.parent.id, body.toAnswer())
            call.respond(answered.toDto(people, clock.now()))
        }
    }
}

private const val MAX_WAIT_SECONDS = 30
