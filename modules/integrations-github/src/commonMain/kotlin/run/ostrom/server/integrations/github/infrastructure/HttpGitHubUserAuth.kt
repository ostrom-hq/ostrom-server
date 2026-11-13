// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.integrations.github.infrastructure

import run.ostrom.server.integrations.github.domain.GitHubAppMisconfiguredException
import run.ostrom.server.integrations.github.domain.GitHubCallException
import run.ostrom.server.integrations.github.domain.GitHubDeviceAuthorization
import run.ostrom.server.integrations.github.domain.GitHubDeviceFlowDisabledException
import run.ostrom.server.integrations.github.domain.GitHubDevicePoll
import run.ostrom.server.integrations.github.domain.GitHubIdentity
import run.ostrom.server.integrations.github.domain.GitHubLinkDeniedException
import run.ostrom.server.integrations.github.domain.GitHubLinkNotFoundException
import run.ostrom.server.integrations.github.domain.GitHubOAuthApp
import run.ostrom.server.integrations.github.domain.GitHubRefresh
import run.ostrom.server.integrations.github.domain.GitHubTokens
import run.ostrom.server.integrations.github.domain.GitHubUserAuthApi
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * [GitHubUserAuthApi] over HTTPS (teamcode `GitHubAppClient`): the device and web authorizations, the refresh and the
 * revocation of a GitHub App's user-to-server tokens. Bodies are read up to 64 KiB and never surface in an error.
 *
 * @param http The client (`expectSuccess = false`).
 * @param apiUrl GitHub's API.
 * @param webUrl GitHub's web address, where the OAuth endpoints are.
 * @param clock The clock, for the expiries.
 */
class HttpGitHubUserAuth(
    private val http: HttpClient,
    private val apiUrl: String,
    private val webUrl: String,
    private val clock: Clock,
) : GitHubUserAuthApi {
    override suspend fun startDevice(clientId: String): GitHubDeviceAuthorization {
        val (status, body) = form(DEVICE_PATH, listOf("client_id" to clientId))
        body?.string("error")?.let { throw oauthFailure(it) }
        if (status != OK || body == null) throw GitHubCallException("GitHub refused the device code ($status).")
        val deviceCode = body.string("device_code")
        val userCode = body.string("user_code")
        val uri = body.string("verification_uri")
        val expiresIn = body.long("expires_in")
        val interval = body.long("interval") ?: DEFAULT_INTERVAL
        val valid = deviceCode != null && deviceCode.isNotBlank() && deviceCode.length <= MAX_TOKEN &&
            userCode != null && USER_CODE.matches(userCode) &&
            uri != null && uri.startsWith("$webUrl/") && uri.length <= MAX_URI &&
            expiresIn != null && expiresIn in 1..MAX_EXPIRES_IN && interval in 1..MAX_POLL_INTERVAL
        if (!valid) throw GitHubCallException("GitHub answered an unexpected device code.")
        return GitHubDeviceAuthorization(deviceCode, userCode, uri, expiresIn.toInt(), interval.toInt())
    }

    override suspend fun pollDevice(clientId: String, deviceCode: String): GitHubDevicePoll {
        val (status, body) = form(
            TOKEN_PATH,
            listOf("client_id" to clientId, "device_code" to deviceCode, "grant_type" to DEVICE_GRANT),
        )
        if (status != OK || body == null) throw GitHubCallException("GitHub refused the device poll ($status).")
        return when (val error = body.string("error")) {
            null -> GitHubDevicePoll.Authorized(tokens(body))
            "authorization_pending" -> GitHubDevicePoll.Pending
            "slow_down" -> GitHubDevicePoll.SlowDown(
                (body.long("interval") ?: SLOW_DOWN_INTERVAL).toInt().coerceIn(1, MAX_POLL_INTERVAL.toInt()),
            )
            "expired_token", "incorrect_device_code" -> GitHubDevicePoll.Expired
            "access_denied" -> GitHubDevicePoll.Denied
            else -> throw oauthFailure(error)
        }
    }

    override fun authorizationUrl(clientId: String, redirectUri: String, state: String, codeChallenge: String): String =
        URLBuilder("$webUrl/login/oauth/authorize").apply {
            parameters.append("client_id", clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("state", state)
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", "S256")
        }.buildString()

    override suspend fun exchangeCode(
        app: GitHubOAuthApp,
        code: String,
        redirectUri: String,
        codeVerifier: String,
    ): GitHubTokens {
        val (status, body) = form(
            TOKEN_PATH,
            listOf(
                "client_id" to app.clientId,
                "client_secret" to app.clientSecret,
                "code" to code,
                "redirect_uri" to redirectUri,
                "code_verifier" to codeVerifier,
            ),
        )
        if (status != OK || body == null) throw GitHubCallException("GitHub refused the code ($status).")
        body.string("error")?.let { throw oauthFailure(it) }
        return tokens(body)
    }

    override suspend fun refresh(app: GitHubOAuthApp, refreshToken: String): GitHubRefresh {
        val (status, body) = form(
            TOKEN_PATH,
            listOf(
                "client_id" to app.clientId,
                "client_secret" to app.clientSecret,
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
            ),
        )
        if (status != OK || body == null) throw GitHubCallException("GitHub refused the refresh ($status).")
        return when (val error = body.string("error")) {
            null -> GitHubRefresh.Refreshed(tokens(body))
            // Only a refusal of the refresh token itself ends the link: a wrong client secret is the administrator's.
            "bad_refresh_token" -> GitHubRefresh.Rejected
            else -> throw oauthFailure(error)
        }
    }

    override suspend fun user(accessToken: String): GitHubIdentity {
        val (status, body) = send(HttpMethod.Get, "$apiUrl$USER_PATH", USER_PATH) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            rest()
        }
        val id = body?.long("id")
        val login = body?.string("login")
        if (status != OK || id == null || id <= 0 || login == null || !LOGIN.matches(login)) {
            throw GitHubCallException("GitHub did not tell whose token this is ($status).")
        }
        return GitHubIdentity(id, login)
    }

    override suspend fun revoke(app: GitHubOAuthApp, accessToken: String) {
        val basic = Base64.Default.encode("${app.clientId}:${app.clientSecret}".encodeToByteArray())
        val path = "/applications/${app.clientId.encodeURLPathPart()}/token"
        val (status, _) = send(HttpMethod.Delete, "$apiUrl$path", path) {
            header(HttpHeaders.Authorization, "Basic $basic")
            rest()
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("access_token", accessToken) }.toString())
        }
        // 404: the token is already invalid, which is the goal.
        if (status != NO_CONTENT && status != NOT_FOUND) throw GitHubCallException("GitHub kept the token ($status).")
    }

    private fun tokens(body: JsonObject): GitHubTokens {
        val access = body.string("access_token")
        if (access == null || access.isBlank() || access.length > MAX_TOKEN) {
            throw GitHubCallException("GitHub answered without a token.")
        }
        val now = clock.now()
        val refresh = body.string("refresh_token")?.takeIf { it.isNotBlank() && it.length <= MAX_TOKEN }
        return GitHubTokens(
            access,
            body.long("expires_in")?.takeIf { it > 0 }?.let { now + it.seconds },
            refresh,
            body.long("refresh_token_expires_in")?.takeIf { it > 0 && refresh != null }?.let { now + it.seconds },
        )
    }

    private fun oauthFailure(error: String): Exception = when (error) {
        "device_flow_disabled" -> GitHubDeviceFlowDisabledException()
        "incorrect_client_credentials" -> GitHubAppMisconfiguredException(
            "GitHub refused the App's client credentials: an administrator updates the client secret.",
        )
        "redirect_uri_mismatch" -> GitHubAppMisconfiguredException(
            "Ostrom's callback URL is not a callback URL of the GitHub App: an administrator adds it.",
        )
        "bad_verification_code" -> GitHubLinkNotFoundException()
        "unverified_user_email" -> GitHubLinkDeniedException(
            "Verify your primary email address on GitHub, then start again.",
        )
        else -> GitHubCallException("GitHub refused the authorization.")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.rest() {
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", API_VERSION)
        header(HttpHeaders.UserAgent, USER_AGENT)
    }

    private suspend fun form(path: String, fields: List<Pair<String, String>>) =
        send(HttpMethod.Post, "$webUrl$path", path) {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            header(HttpHeaders.UserAgent, USER_AGENT)
            setBody(FormDataContent(Parameters.build { fields.forEach { (key, value) -> append(key, value) } }))
        }

    /** The status and the JSON object of the answer (null when it is not one); a body over 64 KiB is refused. */
    private suspend fun send(
        method: HttpMethod,
        url: String,
        path: String,
        configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): Pair<Int, JsonObject?> {
        val response: HttpResponse = http.exchange(method, url, path, configure)
        val bytes = response.bodyAsChannel().readRemaining(MAX_BODY + 1L).readByteArray()
        if (bytes.size > MAX_BODY) throw GitHubCallException("GitHub answered too much on $path.")
        val json = try {
            Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (_: SerializationException) {
            // Not JSON (an HTML error page of a proxy): the status alone decides.
            null
        }
        return response.status.value to json
    }
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

private const val OK = 200
private const val NO_CONTENT = 204
private const val NOT_FOUND = 404
private const val MAX_BODY = 65_536
private const val MAX_TOKEN = 2048
private const val MAX_URI = 512
private const val MAX_EXPIRES_IN = 3600L
private const val MAX_POLL_INTERVAL = 600L
private const val DEFAULT_INTERVAL = 5L
private const val SLOW_DOWN_INTERVAL = 10L
private const val DEVICE_PATH = "/login/device/code"
private const val TOKEN_PATH = "/login/oauth/access_token"
private const val USER_PATH = "/user"
private const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
private const val API_VERSION = "2022-11-28"
private const val USER_AGENT = "ostrom"
private val USER_CODE = Regex("[A-Z0-9-]{4,32}")
private val LOGIN = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")
