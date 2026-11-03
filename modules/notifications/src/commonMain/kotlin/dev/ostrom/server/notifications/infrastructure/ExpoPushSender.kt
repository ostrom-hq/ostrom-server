// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.infrastructure

import dev.ostrom.server.notifications.domain.PushMessage
import dev.ostrom.server.notifications.domain.PushNotSentException
import dev.ostrom.server.notifications.domain.PushSender
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [PushSender] on Expo's push service (`POST /--/api/v2/push/send`), which hands each message to APNs or FCM with
 * the project's keys: one request for an approver's phones, at most a hundred messages each, as Expo takes them.
 *
 * @param http The client.
 * @param url Expo's push endpoint (`OSTROM_EXPO_PUSH_URL`; a fake one in the tests and the end-to-end bench).
 * @param accessToken The project's access token when its push security requires one (`OSTROM_EXPO_ACCESS_TOKEN`).
 */
class ExpoPushSender(
    private val http: HttpClient,
    private val url: String,
    private val accessToken: String?,
) : PushSender {
    override suspend fun send(messages: List<PushMessage>) {
        messages.chunked(MAX_BATCH).forEach { post(it) }
    }

    private suspend fun post(batch: List<PushMessage>) {
        val body = buildJsonArray {
            batch.forEach { message ->
                add(
                    buildJsonObject {
                        put("to", message.to)
                        put("title", message.title)
                        put("body", message.body)
                        put("sound", "default")
                        // The app's `approvals` channel: it hides the text on a lock screen (Android).
                        put("channelId", APPROVALS_CHANNEL)
                        put("data", JsonObject(message.data.mapValues { JsonPrimitive(it.value) }))
                    },
                )
            }
        }
        val answer = answer(body.toString())
        val refusal = when {
            answer == null -> "Expo did not answer."
            !answer.status.isSuccess() -> "Expo refused (${answer.status.value})."
            // 200 with a ticket per message: one in error (rate, size, an unknown device) is a refusal too.
            else -> ticketError(answer.text)?.let { "Expo refused a message ($it)." }
        }
        if (refusal != null) throw PushNotSentException(refusal)
    }

    private class Answer(val status: HttpStatusCode, val text: String)

    /** Expo's answer to [body], or null when it did not answer (connection, timeout). */
    private suspend fun answer(body: String): Answer? = try {
        val response = http.post(url) {
            accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        Answer(response.status, response.bodyAsText())
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}

/** The error code of the first ticket in error (`DeviceNotRegistered`, `MessageRateExceeded`…), or null. */
internal fun ticketError(text: String): String? {
    val answer = try {
        EXPO_JSON.decodeFromString<ExpoAnswer>(text)
    } catch (_: IllegalArgumentException) {
        ExpoAnswer()
    }
    return answer.data.firstOrNull { it.status == "error" }?.let { it.details?.error ?: "error" }
}

private val EXPO_JSON = Json { ignoreUnknownKeys = true }

@Serializable
private data class ExpoAnswer(val data: List<ExpoTicket> = emptyList())

@Serializable
private data class ExpoTicket(val status: String = "ok", val details: ExpoTicketDetails? = null)

@Serializable
private data class ExpoTicketDetails(val error: String? = null)

/** The id of the Android notification channel the app creates for the requests (ostrom-app `services/push`). */
internal const val APPROVALS_CHANNEL = "approvals"

/** The most messages Expo takes in one request. */
internal const val MAX_BATCH = 100
