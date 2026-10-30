// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.infrastructure

import dev.ostrom.server.notifications.domain.EmailMessage
import dev.ostrom.server.notifications.domain.EmailSender
import dev.ostrom.server.notifications.domain.NotificationCallException
import dev.ostrom.server.notifications.domain.PushMessage
import dev.ostrom.server.notifications.domain.PushSender
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * [PushSender] on Expo's push service: one request for every message of an event, at most a hundred, as Expo takes.
 *
 * @param http The client.
 * @param url Expo's push endpoint.
 * @param token Expo's access token, or null.
 */
class ExpoPushSender(private val http: HttpClient, private val url: String, private val token: String?) : PushSender {
    override suspend fun send(messages: List<PushMessage>) {
        for (batch in messages.chunked(MAX_BATCH)) {
            val body = buildJsonArray {
                batch.forEach { message ->
                    add(
                        buildJsonObject {
                            put("to", message.to)
                            put("title", message.title)
                            put("body", message.body)
                            put("sound", "default")
                            put("data", JsonObject(message.data.mapValues { JsonPrimitive(it.value) }))
                        },
                    )
                }
            }
            post(http, url, "Expo", body) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
        }
    }

    private companion object {
        const val MAX_BATCH = 100
    }
}

/**
 * [EmailSender] on Resend.
 *
 * @param http The client.
 * @param apiUrl Resend's API.
 * @param key The API key, or null: then [configured] is false and nothing is sent.
 * @param from The sender.
 */
class ResendEmailSender(
    private val http: HttpClient,
    private val apiUrl: String,
    private val key: String?,
    private val from: String,
) : EmailSender {
    override val configured: Boolean = key != null

    override suspend fun send(message: EmailMessage) {
        val api = key ?: throw NotificationCallException("Resend is not configured on this server.")
        val body = buildJsonObject {
            put("from", from)
            putJsonArray("to") { add(JsonPrimitive(message.to)) }
            put("subject", message.subject)
            put("text", message.text)
        }
        post(http, "$apiUrl/emails", "Resend", body) { header(HttpHeaders.Authorization, "Bearer $api") }
    }
}

// One call: any status but a success, or no answer, is a NotificationCallException naming the service only.
private suspend fun post(
    http: HttpClient,
    url: String,
    service: String,
    body: JsonElement,
    authorize: HttpRequestBuilder.() -> Unit,
) {
    val response: HttpResponse = try {
        http.post(url) {
            authorize()
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw NotificationCallException("$service did not answer.", error)
    }
    if (!response.status.isSuccess()) throw NotificationCallException("$service refused (${response.status.value}).")
}
