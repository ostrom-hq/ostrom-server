// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import run.ostrom.server.integrations.slack.domain.SlackApi
import run.ostrom.server.integrations.slack.domain.SlackCallException
import run.ostrom.server.integrations.slack.domain.SlackMessage
import run.ostrom.server.integrations.slack.domain.SlackPosted
import run.ostrom.server.integrations.slack.domain.SlackUser

/**
 * [SlackApi] on Slack's Web API (`https://slack.com/api/<method>`), with the bot token as a bearer. Slack answers 200
 * with `{"ok": false, "error": "<code>"}` for a refusal: the code is kept, never Slack's text nor the token. A 429, a
 * 5xx, an unreachable Slack or one of Slack's own transient codes is retryable.
 *
 * @param http The client (redirects off, 10 s timeouts).
 * @param baseUrl Slack's API, without a trailing slash; another one only in tests.
 */
class HttpSlackApi(
    private val http: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : SlackApi {
    override suspend fun authTest(token: String): String? = try {
        call("auth.test", token) { }
        null
    } catch (error: SlackCallException) {
        error.code
    }

    override suspend fun lookupByEmail(token: String, email: String): SlackUser? {
        val answer = try {
            call("users.lookupByEmail", token) {
                setBody(FormDataContent(Parameters.build { append("email", email) }))
            }
        } catch (error: SlackCallException) {
            if (error.code == USERS_NOT_FOUND) return null
            throw error
        }
        val user = answer["user"] as? JsonObject ?: throw SlackCallException(MALFORMED, retryable = true)
        val id = user.text("id") ?: throw SlackCallException(MALFORMED, retryable = true)
        return SlackUser(id, deleted = user.flag("deleted"), bot = user.flag("is_bot") || user.flag("is_app_user"))
    }

    override suspend fun postMessage(token: String, userId: String, message: SlackMessage): SlackPosted {
        val answer = call("chat.postMessage", token) { json(message, "channel" to userId, null) }
        val channel = answer.text("channel")
        val ts = answer.text("ts")
        return if (channel != null && ts != null) SlackPosted(channel, ts) else throw SlackCallException(MALFORMED, true)
    }

    override suspend fun updateMessage(token: String, posted: SlackPosted, message: SlackMessage) {
        call("chat.update", token) { json(message, "channel" to posted.channel, posted.ts) }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.json(
        message: SlackMessage,
        channel: Pair<String, String>,
        ts: String?,
    ) {
        val json = buildJsonObject {
            put(channel.first, channel.second)
            ts?.let { put("ts", it) }
            put("text", message.text)
            // Our own links only: Slack never unfurls a preview of them.
            put("unfurl_links", false)
            message.blocks?.let { put("blocks", it) }
        }
        setBody(TextContent(json.toString(), ContentType.Application.Json))
    }

    private suspend fun call(
        method: String,
        token: String,
        build: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): JsonObject {
        val response: HttpResponse = try {
            http.post("$baseUrl/$method") {
                bearerAuth(token)
                build()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw SlackCallException(UNREACHABLE, retryable = true)
        }
        when {
            response.status == HttpStatusCode.TooManyRequests -> throw SlackCallException(RATE_LIMITED, true)
            response.status.value >= SERVER_ERROR -> throw SlackCallException(UNAVAILABLE, true)
        }
        val answer = try {
            Json.parseToJsonElement(response.bodyAsText()) as? JsonObject
        } catch (_: SerializationException) {
            null
        } ?: throw SlackCallException(MALFORMED, retryable = true)
        if (answer.flag("ok")) return answer
        val code = answer.text("error")?.takeIf { CODE.matches(it) } ?: UNKNOWN
        throw SlackCallException(code, retryable = code in TRANSIENT)
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    /** Slack's API. */
    companion object {
        /** Slack's own Web API. */
        const val DEFAULT_BASE_URL: String = "https://slack.com/api"

        private const val USERS_NOT_FOUND = "users_not_found"
        private const val UNREACHABLE = "slack_unreachable"
        private const val UNAVAILABLE = "slack_unavailable"
        private const val RATE_LIMITED = "ratelimited"
        private const val MALFORMED = "slack_malformed"
        private const val UNKNOWN = "slack_error"
        private const val SERVER_ERROR = 500
        private val CODE = Regex("[a-z_]{1,48}")
        private val TRANSIENT = setOf(
            RATE_LIMITED,
            "rate_limited",
            "internal_error",
            "fatal_error",
            "service_unavailable",
            "request_timeout",
        )
    }
}
