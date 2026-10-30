// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack.infrastructure

import dev.ostrom.server.integrations.slack.domain.SlackApi
import dev.ostrom.server.integrations.slack.domain.SlackCallException
import dev.ostrom.server.integrations.slack.domain.SlackText
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [SlackApi] on Slack's Web API, with the bot token. Slack answers 200 with `ok: false` for a refused call: both a
 * non-200 and `ok: false` throw [SlackCallException], with Slack's error code and never the token.
 *
 * @param http The client.
 * @param apiUrl `https://slack.com/api`, or a test server.
 * @param token The bot token, or null: then [configured] is false and every call throws.
 */
class HttpSlackApi(
    private val http: HttpClient,
    private val apiUrl: String,
    private val token: String?,
) : SlackApi {
    override val configured: Boolean = token != null

    override suspend fun openDirectMessage(userId: String): String {
        val answer = call("conversations.open", buildJsonObject { put("users", userId) })
        return answer.channel?.id ?: throw SlackCallException("Slack opened no direct message.")
    }

    override suspend fun post(channel: String, message: SlackText): String {
        val answer = call("chat.postMessage", body(channel, message))
        return answer.ts ?: throw SlackCallException("Slack posted no message.")
    }

    override suspend fun update(channel: String, ts: String, message: SlackText) {
        call("chat.update", JsonObject(body(channel, message) + ("ts" to JsonPrimitive(ts))))
    }

    private fun body(channel: String, message: SlackText) = buildJsonObject {
        put("channel", channel)
        put("text", message.text)
        put("blocks", message.blocks)
    }

    private suspend fun call(method: String, body: JsonObject): Answer {
        val bot = token ?: throw SlackCallException("Slack is not configured on this server.")
        val response = send(method, bot, body)
        val answer = decode(method, response)
        if (!answer.ok) throw SlackCallException("Slack refused $method (${answer.error ?: response.status.value}).")
        return answer
    }

    private suspend fun decode(method: String, response: HttpResponse): Answer = try {
        json.decodeFromString<Answer>(response.bodyAsText())
    } catch (error: SerializationException) {
        throw SlackCallException("Slack answered $method unexpectedly (${response.status.value}).", error)
    }

    private suspend fun send(method: String, bot: String, body: JsonObject): HttpResponse = try {
        http.post("$apiUrl/$method") {
            header(HttpHeaders.Authorization, "Bearer $bot")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw SlackCallException("Slack did not answer $method.", error)
    }

    @Serializable
    private data class Channel(val id: String)

    @Serializable
    private data class Answer(
        val ok: Boolean = false,
        val error: String? = null,
        val channel: Channel? = null,
        val ts: String? = null,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
