// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.presentation

import dev.ostrom.server.kernel.auth.authenticated
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.secrets.presentation.requireCaller
import dev.ostrom.server.usage.domain.IngestUsageEventsUseCase
import dev.ostrom.server.usage.domain.ListUsageEventsUseCase
import dev.ostrom.server.usage.domain.TooManyUsageEventsException
import dev.ostrom.server.usage.domain.UsageRules
import dev.ostrom.server.usage.domain.UsageSummaryUseCase
import dev.ostrom.server.usage.domain.WatchUsageUseCase
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import kotlinx.coroutines.flow.map
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The largest ingestion body read: 500 events of a few hundred bytes each fit well under it. */
private const val MAX_INGEST_BYTES: Long = 1_048_576

/**
 * What the usage routes need.
 *
 * @property callers Resolves the member of each call.
 * @property ingest Records client events.
 * @property summary Totals.
 * @property listEvents Pages the ledger.
 * @property watch Follows it live.
 * @property heartbeat The interval of the live stream's heartbeat comments.
 */
data class UsageRoutesDependencies(
    val callers: CallerResolver,
    val ingest: IngestUsageEventsUseCase,
    val summary: UsageSummaryUseCase,
    val listEvents: ListUsageEventsUseCase,
    val watch: WatchUsageUseCase,
    val heartbeat: Duration = 15.seconds,
)

/**
 * The usage routes: authenticated; an admin reads the whole org, a member only their own usage.
 *
 * @param dependencies The use cases.
 */
fun Route.usageRoutes(dependencies: UsageRoutesDependencies) = with(dependencies) {
    authenticated {
        post<UsageIngestApi> {
            val caller = call.requireCaller(callers, null)
            if ((call.request.contentLength() ?: 0) > MAX_INGEST_BYTES) throw TooManyUsageEventsException()
            val request = call.receive<UsageEventsRequest>()
            val accepted = ingest(caller, request.events.map { it.toClientEvent() })
            call.respond(HttpStatusCode.Accepted, UsageAcceptedResponse(accepted))
        }
        get<OrgUsageApi.Summary> { params ->
            val caller = call.requireCaller(callers, params.parent.orgId)
            call.respond(summary(caller, params.days, params.groupBy.toGroupBy()).toResponse())
        }
        get<OrgUsageApi.Events> { params ->
            val caller = call.requireCaller(callers, params.parent.orgId)
            val limit = params.limit.coerceIn(1, UsageRules.MAX_PAGE)
            val page = listEvents(caller, params.before?.toUsageCursor(), limit)
            val next = page.takeIf { it.size == limit }?.last()?.toCursor()
            call.respond(UsageEventsPageResponse(page.map { it.toResponse() }, next))
        }
        get<OrgUsageApi.Live> { params ->
            val caller = call.requireCaller(callers, params.parent.orgId)
            call.response.header(HttpHeaders.CacheControl, "no-cache")
            // nginx (the web image proxies /api/) buffers responses unless told not to: the events would arrive late.
            call.response.header("X-Accel-Buffering", "no")
            call.respondBytesWriter(ContentType.Text.EventStream) {
                writeEventStream(heartbeat) { onSubscribed ->
                    watch(caller, onSubscribed).map {
                        OstromJson.encodeToString(UsageEventResponse.serializer(), it.toResponse())
                    }
                }
            }
        }
    }
}
