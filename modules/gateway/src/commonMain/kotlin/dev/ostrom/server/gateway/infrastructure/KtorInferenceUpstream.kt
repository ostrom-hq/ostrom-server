// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gateway.infrastructure

import dev.ostrom.server.gateway.domain.ByteSource
import dev.ostrom.server.gateway.domain.InferenceUpstream
import dev.ostrom.server.gateway.domain.UpstreamAnswer
import dev.ostrom.server.gateway.domain.UpstreamRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.utils.io.readAvailable

/**
 * [InferenceUpstream] on a Ktor client. The answer is never buffered: it is read chunk by chunk as the agent receives
 * it. No timeout on the whole call (a long generation streams for minutes); the client's connect and socket timeouts
 * apply.
 *
 * @param client Without redirects (a redirect would carry the credential elsewhere).
 */
class KtorInferenceUpstream(private val client: HttpClient) : InferenceUpstream {
    override suspend fun <T> exchange(request: UpstreamRequest, handle: suspend (UpstreamAnswer) -> T): T =
        client.preparePost(request.url) {
            headers {
                request.headers.filter { it.first != "content-type" }.forEach { (name, value) -> append(name, value) }
            }
            setBody(TextContent(request.body, ContentType.Application.Json))
        }.execute { response ->
            val channel = response.bodyAsChannel()
            val headers = response.headers.entries()
                .associate { (name, values) -> name.lowercase() to values.joinToString(",") }
            val body = ByteSource { buffer -> channel.readAvailable(buffer, 0, buffer.size) }
            handle(UpstreamAnswer(response.status.value, headers, body))
        }
}
