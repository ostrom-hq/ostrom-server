// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0), push/.../teamcode/push/FirebaseFcm.kt; see NOTICE.
package run.ostrom.server.push

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import me.nathanfallet.flareon.core.credentials.GoogleCredentials
import me.nathanfallet.flareon.messaging.FcmService
import me.nathanfallet.flareon.messaging.models.AndroidConfig
import me.nathanfallet.flareon.messaging.models.AndroidPriority
import me.nathanfallet.flareon.messaging.models.ApnsConfig
import me.nathanfallet.flareon.messaging.models.ApnsPayload
import me.nathanfallet.flareon.messaging.models.Aps
import me.nathanfallet.flareon.messaging.models.ApsAlert
import me.nathanfallet.flareon.messaging.models.FcmMessage
import run.ostrom.core.api.models.PushRelay
import run.ostrom.core.api.models.PushRelaySendRequest
import run.ostrom.core.api.models.PushRelaySendResponse

private const val STATUS_NOT_FOUND = 404
private const val STATUS_UNAUTHORIZED = 401
private const val STATUS_REQUEST_TIMEOUT = 408
private const val STATUS_TOO_MANY_REQUESTS = 429
private const val FIRST_SERVER_ERROR = 500
private const val LAST_SERVER_ERROR = 599
private const val MAX_RETRY_AFTER_SECONDS = 3_600L
private const val CONNECT_TIMEOUT_MS = 5_000L
private const val REQUEST_TIMEOUT_MS = 10_000L
private const val APNS_PRIORITY_NOW = "10"

/**
 * FCM through flareon, with the Cloud Run service account (no key file): the same message as a team's own Firebase
 * project sends. The provider's answer is reduced to a status and a short code; its body is never kept.
 *
 * @param credentials The Google credentials of the service account.
 * @param http The client that reaches FCM.
 * @param projectId The Firebase project, or null for the one of the credentials.
 */
public class FirebaseFcm(
    credentials: GoogleCredentials,
    private val http: HttpClient = defaultClient(),
    projectId: String? = null,
) : FcmSender, AutoCloseable {
    private val fcm = FcmService(credentials, projectId, http)

    override suspend fun send(request: PushRelaySendRequest): PushRelaySendResponse = try {
        fcm.sendMessage(message(request))
        PushRelaySendResponse("sent")
    } catch (error: CancellationException) {
        throw error
    } catch (error: ResponseException) {
        val retryAfter = error.response.headers["Retry-After"]?.toLongOrNull()?.coerceIn(1, MAX_RETRY_AFTER_SECONDS)
        when (val status = error.response.status.value) {
            STATUS_NOT_FOUND -> PushRelaySendResponse("unregistered")
            // The relay's own credentials: a token being refreshed, or a project misconfiguration the operator fixes.
            STATUS_UNAUTHORIZED -> PushRelaySendResponse("retry", error = "fcm_auth")
            STATUS_REQUEST_TIMEOUT, STATUS_TOO_MANY_REQUESTS ->
                PushRelaySendResponse("retry", retryAfter, "fcm_rate_limited")
            in FIRST_SERVER_ERROR..LAST_SERVER_ERROR -> PushRelaySendResponse("retry", retryAfter, "fcm_unavailable")
            else -> PushRelaySendResponse("rejected", error = "fcm_$status")
        }
    }

    override fun close(): Unit = http.close()

    /** The message of a request and the client that sends it. */
    public companion object {
        /**
         * The FCM message of a request: the encrypted blob in the data, and for iOS the generic alert shown when the
         * device cannot decrypt it.
         *
         * @param request The request.
         */
        public fun message(request: PushRelaySendRequest): FcmMessage {
            val data = mapOf(PushRelay.DATA_PAYLOAD to request.payload, PushRelay.DATA_VERSION to "1")
            return if (request.platform == "ios") {
                FcmMessage(
                    token = request.token,
                    data = data,
                    apns = ApnsConfig(
                        headers = mapOf(
                            "apns-push-type" to "alert",
                            "apns-priority" to APNS_PRIORITY_NOW,
                            "apns-collapse-id" to request.collapseKey,
                            "apns-expiration" to (Clock.System.now().epochSeconds + request.ttlSeconds).toString(),
                        ),
                        payload = ApnsPayload(
                            Aps(
                                alert = ApsAlert(title = PushRelay.GENERIC_TITLE, body = PushRelay.GENERIC_BODY),
                                sound = "default",
                                mutableContent = 1,
                                threadId = request.collapseKey,
                            ),
                        ),
                    ),
                )
            } else {
                FcmMessage(
                    token = request.token,
                    data = data,
                    android = AndroidConfig(
                        collapseKey = request.collapseKey,
                        priority = AndroidPriority.HIGH,
                        ttl = "${request.ttlSeconds}s",
                    ),
                )
            }
        }

        /** The client of FCM and the metadata server: no redirect, short timeouts, the answer's body never kept. */
        public fun defaultClient(): HttpClient = HttpClient {
            expectSuccess = true
            followRedirects = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; explicitNulls = false }) }
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                socketTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }
    }
}
