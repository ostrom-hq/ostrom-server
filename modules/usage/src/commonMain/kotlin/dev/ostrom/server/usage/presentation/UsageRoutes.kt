// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.presentation

import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.WriteAccess
import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.errors.Pages
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.kernel.http.openConnections
import dev.ostrom.server.secrets.domain.RelayScopes
import dev.ostrom.server.secrets.presentation.requireCaller
import dev.ostrom.server.usage.domain.InvalidUsageEventException
import dev.ostrom.server.usage.domain.UsageRules
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.flow.map
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException

/**
 * The usage routes, on the org of the caller's token: an admin reads the whole org, a member only their own usage.
 * Reading takes a browser session or a console token with [UsageScopes.READ]; recording takes [UsageScopes.WRITE] or
 * an agent session (the CLI's hooks). Device tokens are refused (SECURITY-AUDIT S2-03).
 *
 * @param dependencies The use cases.
 */
fun Route.usageRoutes(dependencies: UsageRoutesDependencies) = with(dependencies) {
    authenticated {
        get<UsageApi> { params ->
            val caller = call.requireCaller(callers, UsageScopes.READ)
            val from = params.from?.toInstantParameter("from")
            val to = params.to?.toInstantParameter("to")
            call.respond(report(caller, from, to, params.member?.toMemberParameter()).toResponse())
        }
        post<UsageApi.Events> {
            // Made for programs: the CLI's hooks (a personal token) and agents record usage (S2-52, declared).
            val caller = call.requireCaller(
                callers,
                UsageScopes.WRITE,
                RelayScopes.AGENT_SESSION,
                writers = WriteAccess.tokens(PrincipalKind.USER, PrincipalKind.AGENT, PrincipalKind.SERVICE),
            )
            // The kernel caps the body at 1 MiB (S2-46), chunked or not: 500 events of a few hundred bytes fit well.
            val bytes = call.receiveChannel().readRemaining().readByteArray()
            val request = try {
                OstromJson.decodeFromString(UsageEventsRequest.serializer(), bytes.decodeToString())
            } catch (_: SerializationException) {
                throw InvalidUsageEventException("The body is not a list of usage events.")
            }
            val accepted = ingest(caller, request.events.map { it.toClientEvent() })
            call.respond(HttpStatusCode.Accepted, UsageAcceptedResponse(accepted))
        }
        get<UsageApi.Summary> { params ->
            val caller = call.requireCaller(callers, UsageScopes.READ)
            call.respond(summary(caller, params.days, params.groupBy.toGroupBy()).toResponse())
        }
        get<UsageApi.Events> { params ->
            val caller = call.requireCaller(callers, UsageScopes.READ)
            val limit = Pages.size(params.limit, UsageRules.MAX_PAGE, UsageRules.MAX_PAGE)
            val page = listEvents(caller, params.before?.toUsageCursor(), limit)
            val next = if (page.size == limit) page.last().toCursor() else null
            call.respond(UsageEventsPageResponse(page.map { it.toResponse() }, next))
        }
    }
    // The live stream stays open as long as its client keeps it: an open connection, counted apart from the server's
    // other calls and from the waits, from before authentication on (P1 memory).
    openConnections {
        authenticated {
            get<UsageApi.Live> {
                val caller = call.requireCaller(callers, UsageScopes.READ)
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                // nginx (the web image proxies /api/) buffers responses unless told not to: the events would arrive
                // late.
                call.response.header("X-Accel-Buffering", "no")
                call.respondBytesWriter(ContentType.Text.EventStream) {
                    writeEventStream(heartbeat, liveLifetime) { onSubscribed ->
                        watch(caller, onSubscribed).map {
                            OstromJson.encodeToString(UsageEventResponse.serializer(), it.toResponse())
                        }
                    }
                }
            }
        }
    }
}
