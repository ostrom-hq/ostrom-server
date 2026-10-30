// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.infrastructure

import dev.ostrom.server.integrations.github.domain.CHECK_NAME
import dev.ostrom.server.integrations.github.domain.CheckRun
import dev.ostrom.server.integrations.github.domain.GitHubApi
import dev.ostrom.server.integrations.github.domain.GitHubCallException
import dev.ostrom.server.integrations.github.domain.GitHubPull
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
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
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * [GitHubApi] over HTTPS as the GitHub App: the App's JWT is exchanged for an installation token restricted to
 * `checks: write` and `pull_requests: read`, kept until a minute before it expires.
 *
 * @param http The client (`expectSuccess = false`).
 * @param apiUrl GitHub's API.
 * @param jwt The App's JWT, or null when the App is not configured: then every call fails.
 * @param clock The clock.
 * @param webUrl GitHub's web address, where the OAuth code of the install is exchanged.
 * @param oauth The App's OAuth client, or null when it has none: then the install cannot be checked and fails.
 */
class HttpGitHubApi(
    private val http: HttpClient,
    private val apiUrl: String,
    private val jwt: AppJwt?,
    private val clock: Clock,
    private val webUrl: String = "https://github.com",
    private val oauth: AppOAuthClient? = null,
) : GitHubApi {
    private val tokens = mutableMapOf<Long, InstallationToken>()
    private val lock = Mutex()

    override suspend fun pullRequest(installationId: Long, repo: String, number: Int): GitHubPull? {
        // The hook of the agent waits for this answer: GitHub has 2 seconds.
        val response = call(installationId, HttpMethod.Get, "/repos/$repo/pulls/$number", null, HEAD_TIMEOUT_MS)
        if (response.status == HttpStatusCode.NotFound) return null
        val pull = decode<Pull>(response, "pull request")
        val open = pull.state == "open" && !pull.merged
        return GitHubPull(pull.number, pull.head.sha, pull.base.ref, open, pull.user.login)
    }

    override suspend fun publishCheck(installationId: Long, repo: String, check: CheckRun, checkRunId: Long?): Long {
        val body = buildJsonObject {
            put("name", CHECK_NAME)
            put("head_sha", check.headSha)
            put("status", check.status)
            put("external_id", check.externalId)
            put("details_url", check.detailsUrl)
            check.conclusion?.let {
                put("conclusion", it)
                put("completed_at", clock.now().toString())
            }
            putJsonObject("output") {
                put("title", check.title)
                put("summary", check.summary)
            }
        }
        val path = "/repos/$repo/check-runs"
        val response = if (checkRunId == null) {
            call(installationId, HttpMethod.Post, path, body)
        } else {
            call(installationId, HttpMethod.Patch, "$path/$checkRunId", body)
        }
        return decode<CheckRunId>(response, "check run").id
    }

    override suspend fun installationAccount(installationId: Long): String? {
        val response = send(HttpMethod.Get, "/app/installations/$installationId", appToken(), null)
        if (response.status == HttpStatusCode.NotFound) return null
        return decode<Installation>(response, "installation").account.login
    }

    override suspend fun userToken(code: String): String? {
        val client = oauth ?: throw GitHubCallException("The GitHub App has no OAuth client on this server.")
        val form = Parameters.build {
            append("client_id", client.id)
            append("client_secret", client.secret)
            append("code", code)
        }
        val response = http.exchange(HttpMethod.Post, "$webUrl$OAUTH_TOKEN_PATH", OAUTH_TOKEN_PATH) {
            header(HttpHeaders.Accept, ContentType.Application.Json.toString())
            header(HttpHeaders.UserAgent, USER_AGENT)
            setBody(FormDataContent(form))
        }
        // GitHub answers 200 with an `error` field for a code it refuses.
        return decode<UserToken>(response, "authorization").accessToken
    }

    override suspend fun userInstallations(userToken: String): Set<Long> {
        val ids = mutableSetOf<Long>()
        var page = 1
        do {
            val path = "/user/installations?per_page=$PAGE_SIZE&page=$page"
            val batch = decode<UserInstallations>(send(HttpMethod.Get, path, userToken, null), "installations")
                .installations
            batch.mapTo(ids) { it.id }
            page++
        } while (batch.size == PAGE_SIZE && page <= MAX_PAGES)
        return ids
    }

    private suspend fun call(
        installationId: Long,
        method: HttpMethod,
        path: String,
        body: JsonObject?,
        timeoutMs: Long? = null,
    ): HttpResponse = send(method, path, installationToken(installationId), body, timeoutMs)

    private suspend fun installationToken(installationId: Long): String = lock.withLock {
        tokens[installationId]?.takeIf { it.expiresAt - clock.now() > REFRESH_BEFORE }?.let { return it.token }
        val permissions = buildJsonObject {
            putJsonObject("permissions") {
                put("checks", "write")
                put("pull_requests", "read")
            }
        }
        val path = "/app/installations/$installationId/access_tokens"
        val response = send(HttpMethod.Post, path, appToken(), permissions)
        val issued = decode<AccessToken>(response, "installation token")
        val token = InstallationToken(issued.token, Instant.parse(issued.expiresAt))
        tokens[installationId] = token
        token.token
    }

    private suspend fun appToken(): String {
        val app = jwt ?: throw GitHubCallException("The GitHub App is not configured on this server.")
        return app.token()
    }

    private suspend fun send(
        method: HttpMethod,
        path: String,
        token: String,
        body: JsonObject?,
        timeoutMs: Long? = null,
    ): HttpResponse = http.exchange(method, "$apiUrl$path", path) {
        headers(token)
        timeoutMs?.let { timeout { requestTimeoutMillis = it } }
        body?.let {
            contentType(ContentType.Application.Json)
            setBody(it.toString())
        }
    }

    private data class InstallationToken(val token: String, val expiresAt: Instant)

    @Serializable
    private data class Ref(val sha: String = "", val ref: String = "")

    @Serializable
    private data class Account(val login: String)

    @Serializable
    private data class Pull(
        val number: Int,
        val state: String,
        val merged: Boolean = false,
        val head: Ref,
        val base: Ref,
        val user: Account,
    )

    @Serializable
    private data class CheckRunId(val id: Long)

    @Serializable
    private data class Installation(val account: Account)

    @Serializable
    private data class UserToken(@SerialName("access_token") val accessToken: String? = null)

    @Serializable
    private data class InstallationId(val id: Long)

    @Serializable
    private data class UserInstallations(val installations: List<InstallationId> = emptyList())

    @Serializable
    private data class AccessToken(val token: String, @SerialName("expires_at") val expiresAt: String)

    private companion object {
        const val HEAD_TIMEOUT_MS = 2_000L
        const val PAGE_SIZE = 100

        // 1,000 installations reachable by one person: beyond, the install is refused rather than read forever.
        const val MAX_PAGES = 10
        const val OAUTH_TOKEN_PATH = "/login/oauth/access_token"
    }
}

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val REFRESH_BEFORE = 1.minutes
private val json = Json { ignoreUnknownKeys = true }

private const val USER_AGENT = "ostrom"

private suspend fun HttpClient.exchange(
    method: HttpMethod,
    url: String,
    path: String,
    configure: HttpRequestBuilder.() -> Unit,
): HttpResponse = try {
    // Not `build`: inside the builder, that name is HttpRequestBuilder.build(), which would drop the configuration.
    request(url) {
        this.method = method
        configure()
    }
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    // A timeout or a refused connection: the message of the cause can carry the URL, never a token.
    throw GitHubCallException("GitHub did not answer ${method.value} $path.", error)
}

private fun HttpRequestBuilder.headers(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
    header(HttpHeaders.Accept, "application/vnd.github+json")
    header("X-GitHub-Api-Version", "2022-11-28")
    header(HttpHeaders.UserAgent, USER_AGENT)
}

private suspend inline fun <reified T> decode(response: HttpResponse, what: String): T {
    if (!response.status.isSuccess()) {
        throw GitHubCallException("GitHub refused the $what (${response.status.value}).")
    }
    return try {
        json.decodeFromString<T>(response.bodyAsText())
    } catch (error: SerializationException) {
        throw GitHubCallException("GitHub answered an unexpected $what.", error)
    }
}
