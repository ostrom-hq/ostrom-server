// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing.infrastructure

import dev.ostrom.server.sharing.domain.AccountCredentials
import dev.ostrom.server.sharing.domain.AccountProvider
import dev.ostrom.server.sharing.domain.OAuthRefresher
import dev.ostrom.server.sharing.domain.RefreshResult
import io.ktor.client.HttpClient
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Renews Claude and Codex OAuth tokens at their providers, with the public client ids of Claude Code and Codex CLI
 * (as TeamCode does). A 400 or 401 answer means the refresh token is gone; anything else is retried later.
 *
 * @param client The HTTP client.
 * @param clock The time.
 * @param endpoints Token endpoint per provider (tests point them at a fake).
 * @param timeout How long a refresh may take.
 */
class HttpOAuthRefresher(
    private val client: HttpClient,
    private val clock: Clock,
    private val endpoints: Map<AccountProvider, String> = ENDPOINTS,
    private val timeout: Duration = 15.seconds,
) : OAuthRefresher {
    override suspend fun refresh(provider: AccountProvider, credentials: AccountCredentials): RefreshResult = try {
        withTimeoutOrNull(timeout) {
            client.preparePost(endpoints.getValue(provider)) {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("grant_type", "refresh_token")
                        put("refresh_token", credentials.refreshToken)
                        put("client_id", CLIENT_IDS.getValue(provider))
                    }.toString(),
                )
            }.execute { response ->
                when (response.status) {
                    HttpStatusCode.OK -> {
                        val body = response.bodyAsChannel().readRemaining(MAX_BODY).readByteArray().decodeToString()
                        parse(body, credentials)
                    }
                    in REVOKED -> RefreshResult.Revoked
                    else -> RefreshResult.Unavailable
                }
            }
        } ?: RefreshResult.Unavailable
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        RefreshResult.Unavailable
    }

    private fun parse(body: String, previous: AccountCredentials): RefreshResult {
        val value = try {
            Json.parseToJsonElement(body) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        val access = value?.string("access_token")
        val refresh = value?.string("refresh_token") ?: previous.refreshToken
        val seconds = (value?.get("expires_in") as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..MAX_EXPIRES_IN }
        return if (access == null || seconds == null) {
            RefreshResult.Unavailable
        } else {
            RefreshResult.Renewed(AccountCredentials(access, refresh, clock.now() + seconds.seconds))
        }
    }

    private fun JsonObject.string(key: String): String? {
        val value = this[key] as? JsonPrimitive
        return if (value == null || !value.isString || value.content.isBlank()) null else value.content
    }

    /** The providers' endpoints and client ids. */
    companion object {
        /** The providers' token endpoints. */
        val ENDPOINTS: Map<AccountProvider, String> = mapOf(
            AccountProvider.CLAUDE to "https://platform.claude.com/v1/oauth/token",
            AccountProvider.CODEX to "https://auth.openai.com/oauth/token",
        )

        /** The public OAuth client ids of Claude Code and Codex CLI. */
        val CLIENT_IDS: Map<AccountProvider, String> = mapOf(
            AccountProvider.CLAUDE to "9d1c250a-e61b-44d9-88ed-5944d1962f5e",
            AccountProvider.CODEX to "app_EMoamEEZ73f0CkXaXp7hrann",
        )

        private const val MAX_BODY = 256L * 1024
        private const val MAX_EXPIRES_IN = 31_536_000L
        private val REVOKED = setOf(HttpStatusCode.BadRequest, HttpStatusCode.Unauthorized)
    }
}
