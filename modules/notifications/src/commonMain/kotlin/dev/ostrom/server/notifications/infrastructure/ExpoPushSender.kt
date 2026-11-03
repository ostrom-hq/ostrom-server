// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.infrastructure

import dev.ostrom.server.notifications.domain.PushMessage
import dev.ostrom.server.notifications.domain.PushNotSentException
import dev.ostrom.server.notifications.domain.PushSender
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
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
                        put("data", JsonObject(message.data.mapValues { JsonPrimitive(it.value) }))
                    },
                )
            }
        }
        val status = try {
            http.post(url) {
                accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.status
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw PushNotSentException("Expo did not answer.")
        }
        if (!status.isSuccess()) throw PushNotSentException("Expo refused (${status.value}).")
    }
}

/** The most messages Expo takes in one request. */
internal const val MAX_BATCH = 100
