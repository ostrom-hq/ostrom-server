// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.basicAuth
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import run.ostrom.server.integrations.discord.domain.DiscordApi
import run.ostrom.server.integrations.discord.domain.DiscordApp
import run.ostrom.server.integrations.discord.domain.DiscordCallException
import run.ostrom.server.integrations.discord.domain.DiscordMessage
import run.ostrom.server.integrations.discord.domain.DiscordPosted
import run.ostrom.server.integrations.discord.domain.DiscordUser
import kotlin.math.ceil

/**
 * [DiscordApi] on Discord's HTTP API (`https://discord.com/api/v10`), with `Bot <token>` or the person's bearer. A
 * refusal keeps Discord's JSON error code (`discord_50007`, cannot send messages to this user) or the status
 * (`discord_http_403`), never Discord's text nor a token. A 429 waits the `retry_after` Discord asks for once when it
 * is short, and is retryable otherwise; a 5xx or an unreachable Discord is retryable; any other refusal is not.
 *
 * @param http The client (redirects off, 10 s timeouts).
 * @param baseUrl Discord's API, without a trailing slash; another one only in tests.
 * @param pause Waits before the one retry of a short 429 (milliseconds); a test's own.
 */
@Suppress("TooManyFunctions") // One function per Discord route and its helpers.
class HttpDiscordApi(
    private val http: HttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : DiscordApi {
    override suspend fun checkBot(botToken: String): String? = try {
        call(HttpMethod.Get, "/users/@me") { bot(botToken) }
        null
    } catch (error: DiscordCallException) {
        // Discord down is not a refused token: the caller says so.
        if (error.retryable) throw error
        error.code
    }

    override suspend fun exchangeCode(app: DiscordApp, code: String, redirectUri: String): String {
        val answer = call(HttpMethod.Post, "/oauth2/token") {
            basicAuth(app.applicationId, app.clientSecret)
            setBody(
                FormDataContent(
                    Parameters.build {
                        append("grant_type", "authorization_code")
                        append("code", code)
                        append("redirect_uri", redirectUri)
                    },
                ),
            )
        }
        return answer.objectOrNull()?.text("access_token") ?: throw malformed()
    }

    override suspend fun currentUser(accessToken: String): DiscordUser {
        val user = call(HttpMethod.Get, "/users/@me") { header(HttpHeaders.Authorization, "Bearer $accessToken") }
            .objectOrNull()
        val id = user?.text("id")?.takeIf { SNOWFLAKE.matches(it) } ?: throw malformed()
        return DiscordUser(id, user.text("username").orEmpty())
    }

    override suspend fun inGuild(app: DiscordApp, userId: String): Boolean = try {
        call(HttpMethod.Get, "/guilds/${app.guildId}/members/$userId") { bot(app.botToken) }
        true
    } catch (error: DiscordCallException) {
        // 10007 Unknown Member: not in the server; anything else is a failure of the call.
        if (error.code == UNKNOWN_MEMBER) false else throw error
    }

    override suspend fun openDm(botToken: String, userId: String): String {
        val answer = call(HttpMethod.Post, "/users/@me/channels") {
            bot(botToken)
            json(buildJsonObject { put("recipient_id", userId) })
        }
        return answer.objectOrNull()?.text("id") ?: throw malformed()
    }

    override suspend fun postMessage(botToken: String, channelId: String, message: DiscordMessage): DiscordPosted {
        val answer = call(HttpMethod.Post, "/channels/$channelId/messages") {
            bot(botToken)
            json(message.toJson())
        }
        val id = answer.objectOrNull()?.text("id") ?: throw malformed()
        return DiscordPosted(channelId, id)
    }

    override suspend fun editMessage(botToken: String, posted: DiscordPosted, message: DiscordMessage) {
        call(HttpMethod.Patch, "/channels/${posted.channelId}/messages/${posted.messageId}") {
            bot(botToken)
            json(message.toJson())
        }
    }

    override suspend fun deleteMessage(botToken: String, posted: DiscordPosted) {
        call(HttpMethod.Delete, "/channels/${posted.channelId}/messages/${posted.messageId}") { bot(botToken) }
    }

    private fun DiscordMessage.toJson(): JsonObject = buildJsonObject {
        put("content", content)
        components?.let { put("components", it) }
        // A person's text never pings anyone.
        putJsonObject("allowed_mentions") { putJsonArray("parse") { } }
    }

    private fun HttpRequestBuilder.bot(token: String) = header(HttpHeaders.Authorization, "Bot $token")

    private fun HttpRequestBuilder.json(body: JsonObject) =
        setBody(TextContent(body.toString(), ContentType.Application.Json))

    // The answer's JSON (null for an empty 204), or the failure.
    @Suppress("ThrowsCount") // Unreachable, rate limited, failing, garbled or refused: each its own code.
    private suspend fun call(
        method: HttpMethod,
        path: String,
        configure: HttpRequestBuilder.() -> Unit,
    ): JsonElement? {
        var response = send(method, path, configure)
        val wait = response?.takeIf { it.status == HttpStatusCode.TooManyRequests }?.let { retryAfterMillis(it) }
        if (wait != null && wait <= MAX_WAIT_MS) {
            pause(wait)
            response = send(method, path, configure)
        }
        val answered = response ?: throw DiscordCallException(UNREACHABLE, retryable = true)
        val status = answered.status
        val text = answered.bodyAsText()
        return when {
            status == HttpStatusCode.TooManyRequests -> throw DiscordCallException(RATE_LIMITED, retryable = true)
            status.value >= SERVER_ERROR -> throw DiscordCallException(UNAVAILABLE, retryable = true)
            status.isSuccess() -> answer(text)
            else -> throw DiscordCallException(refusal(status, text), retryable = false)
        }
    }

    // A success's JSON: none for an empty answer (204), else what it says, which must be JSON.
    private fun answer(text: String): JsonElement? {
        if (text.isBlank()) return null
        return parse(text) ?: throw malformed()
    }

    // Null when Discord is unreachable: no answer to read.
    private suspend fun send(
        method: HttpMethod,
        path: String,
        configure: HttpRequestBuilder.() -> Unit,
    ): HttpResponse? = try {
        // Named configure, not build: HttpRequestBuilder.build() would win and leave the body unset.
        http.request("$baseUrl$path") {
            this.method = method
            configure(this)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    // Discord's `retry_after` (seconds, in the body), else its `Retry-After` header.
    private suspend fun retryAfterMillis(response: HttpResponse): Long? {
        val body = parse(response.bodyAsText()).objectOrNull()
        val seconds = (body?.get("retry_after") as? JsonPrimitive)?.doubleOrNull
            ?: response.headers[HttpHeaders.RetryAfter]?.toDoubleOrNull()
        return seconds?.takeIf { it >= 0 }?.let { ceil(it * MS).toLong() }
    }

    private fun refusal(status: HttpStatusCode, text: String): String {
        val code = (parse(text).objectOrNull()?.get("code") as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        return if (code != null && code > 0) "discord_$code" else "discord_http_${status.value}"
    }

    private fun parse(text: String): JsonElement? = try {
        Json.parseToJsonElement(text)
    } catch (_: SerializationException) {
        null
    }

    private fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun malformed() = DiscordCallException(MALFORMED, retryable = true)

    /** Discord's API. */
    companion object {
        /** Discord's own API, version 10. */
        const val DEFAULT_BASE_URL: String = "https://discord.com/api/v10"

        private const val UNKNOWN_MEMBER = "discord_10007"
        private const val UNREACHABLE = "discord_unreachable"
        private const val UNAVAILABLE = "discord_unavailable"
        private const val RATE_LIMITED = "discord_rate_limited"
        private const val MALFORMED = "discord_malformed"
        private const val SERVER_ERROR = 500
        private const val MS = 1000.0
        private const val MAX_WAIT_MS = 5_000L
        private val SNOWFLAKE = Regex("[0-9]{17,20}")
    }
}
