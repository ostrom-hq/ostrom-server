// SPDX-License-Identifier: Apache-2.0
// Ported from TeamCode (Apache-2.0),
// infrastructure/src/main/kotlin/me/nathanfallet/teamcode/infrastructure/push/FirebasePushSender.kt; see NOTICE.
package run.ostrom.server.notifications.infrastructure

import run.ostrom.server.notifications.domain.EncryptedPush
import run.ostrom.server.notifications.domain.FcmSender
import run.ostrom.server.notifications.domain.FirebaseServiceAccount
import run.ostrom.server.notifications.domain.GOOGLE_TOKEN_URI
import run.ostrom.server.notifications.domain.PushResult
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Sends with the instance's own Firebase project (FCM HTTP v1), the message TeamCode's relay and Firebase sender send:
 * a generic visible alert on iOS (`mutable-content`, the app's notification service extension replaces it with the
 * decrypted text), a high-priority data message on Android, the encrypted payload in the data either way
 * (`{"p": …, "v": "1"}`). It signs its own OAuth assertion with the service account's key (RS256) and keeps the
 * access token until a minute before it expires. Never logs the token, the payload or an answer.
 *
 * @param account The service account.
 * @param http The client, `expectSuccess = false`.
 * @param clock The clock of the assertion and of the push's expiry.
 * @param fcmUrl FCM's API (a fake one in the tests).
 */
class FirebasePushSender(
    private val account: FirebaseServiceAccount,
    private val http: HttpClient,
    private val clock: Clock,
    private val fcmUrl: String = FCM_URL,
) : FcmSender {
    private val tokens = Mutex()
    private var token: Pair<String, Instant>? = null

    override suspend fun send(push: EncryptedPush): PushResult {
        val access = accessTokenOrNull() ?: return PushResult.retry("fcm_token")
        return outcome(post(access, push))
    }

    private suspend fun outcome(answer: Answer?): PushResult = when (answer?.status) {
        null -> PushResult.retry("fcm_unreachable")
        in SUCCESS -> PushResult.SENT
        // Only FCM's UNREGISTERED forgets the phone: a 404 for a project that is not found (a wrong project_id, the
        // API not enabled) would otherwise forget every phone.
        NOT_FOUND -> if (answer.unregistered) PushResult.UNREGISTERED else PushResult.rejected("fcm_404")
        // The access token was revoked or expired early: the next attempt asks for another.
        UNAUTHORIZED -> {
            tokens.withLock { token = null }
            PushResult.retry("fcm_unauthorized")
        }
        TIMEOUT, TOO_MANY -> PushResult.retry("fcm_rate_limited")
        in SERVER_ERRORS -> PushResult.retry("fcm_unavailable")
        else -> PushResult.rejected("fcm_${answer.status}")
    }

    /**
     * FCM's answer to a push.
     *
     * @property status Its status.
     * @property unregistered Whether its error is `UNREGISTERED` (read only for a 404, never logged).
     */
    private class Answer(val status: Int, val unregistered: Boolean)

    // FCM's answer for the push, or null when it did not answer (connection, timeout).
    private suspend fun post(access: String, push: EncryptedPush): Answer? = try {
        val response = http.post("$fcmUrl/v1/projects/${account.projectId}/messages:send") {
            header(HttpHeaders.Authorization, "Bearer $access")
            contentType(ContentType.Application.Json)
            setBody(message(push, clock.now()).toString())
        }
        val status = response.status.value
        Answer(status, status == NOT_FOUND && UNREGISTERED in response.bodyAsText())
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    // The access token, or null when Google refused the assertion or did not answer.
    private suspend fun accessTokenOrNull(): String? = try {
        accessToken()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    // From Google's token endpoint with a signed assertion, kept until a minute before it expires.
    private suspend fun accessToken(): String? = tokens.withLock {
        val now = clock.now()
        val kept = token?.takeIf { it.second > now }?.first
        kept ?: run {
            val assertion = assertion(now)
            val response = http.submitForm(
                GOOGLE_TOKEN_URI,
                parameters {
                    append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                    append("assertion", assertion)
                },
            )
            accessTokenOf(response)?.also { (access, expiresIn) ->
                token = access to now + (expiresIn - EARLY_SECONDS).seconds
            }?.first
        }
    }

    private suspend fun assertion(now: Instant): String {
        val header = buildJsonObject {
            put("alg", "RS256")
            put("typ", "JWT")
            put("kid", account.privateKeyId)
        }.toString()
        val claims = buildJsonObject {
            put("iss", account.clientEmail)
            put("scope", SCOPE)
            put("aud", GOOGLE_TOKEN_URI)
            put("iat", now.epochSeconds)
            put("exp", now.epochSeconds + ASSERTION_SECONDS)
        }.toString()
        val input = "${URL_SAFE.encode(header.encodeToByteArray())}.${URL_SAFE.encode(claims.encodeToByteArray())}"
        val key = CryptographyProvider.Default.get(RSA.PKCS1).privateKeyDecoder(SHA256)
            .decodeFromByteArray(RSA.PrivateKey.Format.PEM.Generic, account.privateKey.encodeToByteArray())
        val signature = key.signatureGenerator().generateSignature(input.encodeToByteArray())
        return "$input.${URL_SAFE.encode(signature)}"
    }

    private suspend fun accessTokenOf(response: HttpResponse): Pair<String, Int>? {
        val root = if (response.status.isSuccess()) jsonObjectOf(response.bodyAsText()) else null
        val access = (root?.get("access_token") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val expiresIn = (root?.get("expires_in") as? JsonPrimitive)?.intOrNull ?: DEFAULT_EXPIRY_SECONDS
        return access?.let { it to expiresIn }
    }

    /** The FCM message of a push, shaped as TeamCode's (docs/notifications.md, "What the provider sees"). */
    companion object {
        /** What iOS shows when the extension cannot decrypt the push (an old app, no time). */
        const val GENERIC_TITLE: String = "Ostrom"

        /** The generic text. */
        const val GENERIC_BODY: String = "A request waits for your yes"

        /**
         * @param push The push.
         * @param now The time its expiry counts from.
         *
         * @return The body of `messages:send`.
         */
        fun message(push: EncryptedPush, now: Instant): JsonObject = buildJsonObject {
            putJsonObject("message") {
                put("token", push.token)
                putJsonObject("data") {
                    put("p", push.payload)
                    put("v", "1")
                }
                if (push.platform == "ios") {
                    putJsonObject("apns") {
                        putJsonObject("headers") {
                            put("apns-push-type", "alert")
                            put("apns-priority", "10")
                            put("apns-collapse-id", push.collapseKey)
                            put("apns-expiration", (now.epochSeconds + push.ttlSeconds).toString())
                        }
                        putJsonObject("payload") {
                            putJsonObject("aps") {
                                putJsonObject("alert") {
                                    put("title", GENERIC_TITLE)
                                    put("body", GENERIC_BODY)
                                }
                                put("sound", "default")
                                put("mutable-content", 1)
                                put("thread-id", push.collapseKey)
                            }
                        }
                    }
                } else {
                    putJsonObject("android") {
                        put("collapse_key", push.collapseKey)
                        put("priority", "high")
                        put("ttl", "${push.ttlSeconds}s")
                    }
                }
            }
        }
    }
}

/** FCM's API. */
internal const val FCM_URL = "https://fcm.googleapis.com"

private const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
private const val ASSERTION_SECONDS = 3600L
private const val DEFAULT_EXPIRY_SECONDS = 3600
private const val EARLY_SECONDS = 60
private const val NOT_FOUND = 404
private const val UNREGISTERED = "UNREGISTERED"
private const val UNAUTHORIZED = 401
private const val TIMEOUT = 408
private const val TOO_MANY = 429
private val SUCCESS = 200..299
private val SERVER_ERRORS = 500..599
// The JSON object a text holds, or null.
private fun jsonObjectOf(text: String): JsonObject? = try {
    Json.parseToJsonElement(text) as? JsonObject
} catch (_: SerializationException) {
    null
}

private val URL_SAFE = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
