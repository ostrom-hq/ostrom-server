// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.presentation

import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.domain.RelayScopes
import dev.ostrom.server.secrets.presentation.requireCaller
import dev.ostrom.server.usage.domain.IngestUsageEventsUseCase
import dev.ostrom.server.usage.domain.InvalidUsageEventException
import dev.ostrom.server.usage.domain.ListUsageEventsUseCase
import dev.ostrom.server.usage.domain.TooManyUsageEventsException
import dev.ostrom.server.usage.domain.UsageReportUseCase
import dev.ostrom.server.usage.domain.UsageRules
import dev.ostrom.server.usage.domain.UsageSummaryUseCase
import dev.ostrom.server.usage.domain.WatchUsageUseCase
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The largest ingestion body read: 500 events of a few hundred bytes each fit well under it. */
private const val MAX_INGEST_BYTES: Long = 1_048_576

/**
 * What the usage routes need.
 *
 * @property callers Resolves the member of each call.
 * @property report Spending over a period.
 * @property ingest Records client events.
 * @property summary Totals.
 * @property listEvents Pages the ledger.
 * @property watch Follows it live.
 * @property heartbeat The interval of the live stream's heartbeat comments.
 * @property liveLifetime How long one live stream lasts before the client reconnects and is checked again.
 */
data class UsageRoutesDependencies(
    val callers: CallerResolver,
    val report: UsageReportUseCase,
    val ingest: IngestUsageEventsUseCase,
    val summary: UsageSummaryUseCase,
    val listEvents: ListUsageEventsUseCase,
    val watch: WatchUsageUseCase,
    val heartbeat: Duration = 15.seconds,
    val liveLifetime: Duration = 10.minutes,
)

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
            val caller = call.requireCaller(callers, UsageScopes.WRITE, RelayScopes.AGENT_SESSION)
            // Read to one byte past the limit whatever the headers say: a chunked body declares no length.
            val bytes = call.receiveChannel().readRemaining(MAX_INGEST_BYTES + 1).readByteArray()
            if (bytes.size > MAX_INGEST_BYTES) throw TooManyUsageEventsException()
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
            val limit = params.limit.coerceIn(1, UsageRules.MAX_PAGE)
            val page = listEvents(caller, params.before?.toUsageCursor(), limit)
            val next = if (page.size == limit) page.last().toCursor() else null
            call.respond(UsageEventsPageResponse(page.map { it.toResponse() }, next))
        }
        get<UsageApi.Live> {
            val caller = call.requireCaller(callers, UsageScopes.READ)
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            // nginx (the web image proxies /api/) buffers responses unless told not to: the events would arrive late.
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
