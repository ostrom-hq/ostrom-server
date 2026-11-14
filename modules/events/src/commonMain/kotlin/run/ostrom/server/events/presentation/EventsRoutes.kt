// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.events.presentation

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.resources.get
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.io.IOException
import run.ostrom.server.events.domain.EventFeed
import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.auth.requirePrincipal
import run.ostrom.server.kernel.http.openConnections

/**
 * The organization's live event feed (`GET /api/v1/events`): an open connection, counted apart from the server's other
 * calls. Every refusal (scope, organization, cursor, types) is a JSON error before the stream starts; then frames
 * `id: <seq>`, `event: <type>`, `data: <OrgEvent>`, `event: gap` first when the cursor's events were purged, a
 * keep-alive comment every 15 s of silence, and `event: denied` before the end when the credential stopped being valid.
 *
 * @param dependencies The resolver, the credentials and the feed.
 */
fun Route.eventsRoutes(dependencies: EventsRoutesDependencies) = with(dependencies) {
    openConnections {
        authenticated {
            get<EventsApi> { params ->
                val principal = call.requirePrincipal()
                val (member, audience) = orgs.readerOf(principal)
                val after = cursorOf(call.request.headers[LAST_EVENT_ID], params.after)
                val feed = openFeed(member.orgId, audience, after, typesOf(params.types)) {
                    orgs.stillReads(credentials, principal, member, audience)
                }
                call.stream(feed)
            }
        }
    }
}

/** The header an `EventSource` resumes with. */
private const val LAST_EVENT_ID = "Last-Event-ID"

private suspend fun ApplicationCall.stream(feed: EventFeed) {
    response.header(HttpHeaders.CacheControl, "no-cache")
    // nginx (the web image proxies /api/) buffers responses unless told not to: the events would arrive late.
    response.header("X-Accel-Buffering", "no")
    respondBytesWriter(ContentType.Text.EventStream) { follow(feed) }
}

private suspend fun ByteWriteChannel.follow(feed: EventFeed) {
    suspend fun send(text: String) {
        writeStringUtf8(text)
        flush()
    }
    try {
        send("retry: $RETRY_MILLIS\n\n")
        feed.gapOldest?.let { send(gapFrame(it)) }
        feed.follow(
            emit = { events -> send(events.joinToString("") { eventFrame(it) }) },
            idle = { send(": keep-alive\n\n") },
        )
        send(DENIED_FRAME)
    } catch (_: IOException) {
        // The client closed the stream: there is nobody left to answer, the response simply ends.
    }
}

/** How long an `EventSource` waits before it reconnects. */
private const val RETRY_MILLIS = 2_000
