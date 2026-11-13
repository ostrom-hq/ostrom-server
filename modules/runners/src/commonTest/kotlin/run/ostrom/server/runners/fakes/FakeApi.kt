// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.fakes

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf

/** A recorded call to a fake HTTP API. */
data class ApiCall(
    val method: String,
    val path: String,
    val query: Map<String,
    String>,
    val body: String?,
    val authorization: String?,
)

/**
 * A fake HTTP API: answers each call with [answer] (path → status and body), 200 `{}` by default, and records it.
 */
class FakeApi {
    val calls = mutableListOf<ApiCall>()

    /** Answers by "METHOD path"; a list answers in turn, its last element repeats. */
    val answers = mutableMapOf<String, MutableList<Pair<HttpStatusCode, String>>>()

    fun on(method: String, path: String, vararg answers: Pair<HttpStatusCode, String>) {
        this.answers["$method $path"] = answers.toMutableList()
    }

    val client = HttpClient(MockEngine { request -> handle(request) }) { expectSuccess = false }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val call = ApiCall(
            request.method.value,
            request.url.encodedPath,
            request.url.parameters.entries().associate { it.key to it.value.first() },
            (request.body as? TextContent)?.text,
            request.headers[HttpHeaders.Authorization],
        )
        calls += call
        val queue = answers["${call.method} ${call.path}"]
        val (status, body) = when {
            queue == null -> HttpStatusCode.OK to "{}"
            queue.size > 1 -> queue.removeAt(0)
            else -> queue.first()
        }
        return respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    /** The calls as "METHOD path". */
    fun lines(): List<String> = calls.map { "${it.method} ${it.path}" }
}
