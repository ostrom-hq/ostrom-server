// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.notifications.domain.DEFAULT_EXPO_PUSH_URL
import run.ostrom.server.notifications.domain.EXPO_ACCESS_TOKEN_FIELD
import run.ostrom.server.notifications.domain.EXPO_PUSH_URL_FIELD
import run.ostrom.server.notifications.domain.PUSH_SETTINGS
import run.ostrom.server.notifications.domain.PushMessage
import run.ostrom.server.notifications.domain.PushNotSentException
import run.ostrom.server.notifications.domain.PushSender
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
 * The endpoint and the access token are the instance's push settings ([PUSH_SETTINGS]), read at each send: a change
 * an administrator saves applies to the next push.
 *
 * @param http The client.
 * @param settings The instance's settings (Expo's endpoint, a fake one in the tests and the end-to-end bench, and
 *   the project's access token when its push security requires one).
 */
class ExpoPushSender(
    private val http: HttpClient,
    private val settings: InstanceSettings,
) : PushSender {
    override suspend fun send(messages: List<PushMessage>) {
        val push = settings.read(PUSH_SETTINGS.name)
        // Trimmed, a blank one unset, as the variables they are imported from were read (a mounted Secret's newline).
        val url = push[EXPO_PUSH_URL_FIELD].orEmpty().trim().ifEmpty { DEFAULT_EXPO_PUSH_URL }
        val accessToken = push[EXPO_ACCESS_TOKEN_FIELD].orEmpty().trim().ifEmpty { null }
        messages.chunked(MAX_BATCH).forEach { post(it, url, accessToken) }
    }

    private suspend fun post(batch: List<PushMessage>, url: String, accessToken: String?) {
        val body = buildJsonArray {
            batch.forEach { message ->
                add(
                    buildJsonObject {
                        put("to", message.to)
                        put("title", message.title)
                        put("body", message.body)
                        put("sound", "default")
                        // The app's `requests` channel, which hides the text on a lock screen (Android).
                        put("channelId", APPROVALS_CHANNEL)
                        put("data", JsonObject(message.data.mapValues { JsonPrimitive(it.value) }))
                    },
                )
            }
        }
        val answer = answer(body.toString(), url, accessToken)
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
    private suspend fun answer(body: String, url: String, accessToken: String?): Answer? = try {
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
internal const val APPROVALS_CHANNEL = "requests"

/** The most messages Expo takes in one request. */
internal const val MAX_BATCH = 100
