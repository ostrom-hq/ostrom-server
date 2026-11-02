// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github.infrastructure

import dev.ostrom.server.integrations.github.domain.CHECK_NAME
import dev.ostrom.server.integrations.github.domain.CheckRun
import dev.ostrom.server.integrations.github.domain.CreatedGitHubApp
import dev.ostrom.server.integrations.github.domain.GitHubApi
import dev.ostrom.server.integrations.github.domain.GitHubCallException
import dev.ostrom.server.integrations.github.domain.GitHubCiApi
import dev.ostrom.server.integrations.github.domain.GitHubCiRun
import dev.ostrom.server.integrations.github.domain.GitHubDirectoryApi
import dev.ostrom.server.integrations.github.domain.GitHubManifestApi
import dev.ostrom.server.integrations.github.domain.GitHubPeopleApi
import dev.ostrom.server.integrations.github.domain.GitHubPull
import dev.ostrom.server.integrations.github.domain.GitHubRepo
import dev.ostrom.server.integrations.github.domain.GitHubTeam
import dev.ostrom.server.integrations.github.domain.isRepositoryName
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
import io.ktor.http.encodeURLParameter
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
 * `checks: write` and `pull_requests: read`, kept until a minute before it expires; the same token reads a commit's
 * check runs ([GitHubCiApi]). The directory
 * ([GitHubDirectoryApi]) is read with tokens of their own, read-only: `metadata` for the repositories, `members` for
 * the teams. The manifest conversion ([GitHubManifestApi]) is the one call without a credential: the code is the
 * proof. The person's calls of the install ([GitHubPeopleApi]) go through [HttpGitHubPeople].
 *
 * @param http The client (`expectSuccess = false`).
 * @param apiUrl GitHub's API (`https://api.github.com`, or `https://<host>/api/v3` for GitHub Enterprise Server).
 * @param jwt The App's JWT, read at each exchange (the App can be created after the start), or null when the App is
 *   not configured: then every authenticated call fails.
 * @param clock The clock.
 * @param webUrl GitHub's web address, where the OAuth code of the install is exchanged.
 * @param oauth The App's OAuth client, read at each exchange, or null when it has none: then the install fails.
 */
class HttpGitHubApi(
    private val http: HttpClient,
    private val apiUrl: String,
    private val jwt: suspend () -> AppJwt?,
    private val clock: Clock,
    webUrl: String,
    oauth: suspend () -> AppOAuthClient?,
) : GitHubApi,
    GitHubCiApi,
    GitHubDirectoryApi,
    GitHubManifestApi,
    GitHubPeopleApi by HttpGitHubPeople(http, apiUrl, webUrl, oauth) {
    private val tokens = mutableMapOf<Pair<Long, Map<String, String>>, InstallationToken>()
    private val lock = Mutex()

    // GitHub Enterprise Server serves GraphQL at /api/graphql, next to the REST API's /api/v3.
    private val graphqlUrl = if (apiUrl.endsWith(ENTERPRISE_REST)) {
        apiUrl.removeSuffix(ENTERPRISE_REST) + "/api"
    } else {
        apiUrl
    }

    override suspend fun pullRequest(installationId: Long, repo: String, number: Int): GitHubPull? {
        // The hook of the agent waits for this answer: GitHub has 2 seconds.
        val response = call(installationId, HttpMethod.Get, "${repoPath(repo)}/pulls/$number", null, HEAD_TIMEOUT_MS)
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
        val path = "${repoPath(repo)}/check-runs"
        val response = if (checkRunId == null) {
            call(installationId, HttpMethod.Post, path, body)
        } else {
            call(installationId, HttpMethod.Patch, "$path/$checkRunId", body)
        }
        return decode<CheckRunId>(response, "check run").id
    }

    override suspend fun checkRuns(installationId: Long, repo: String, sha: String, name: String): List<GitHubCiRun> {
        // `checks: write` reads them too: the same token as the gate's check.
        val query = "check_name=${name.encodeURLParameter()}&filter=latest&per_page=$PER_PAGE"
        val response = call(installationId, HttpMethod.Get, "${repoPath(repo)}/commits/$sha/check-runs?$query", null)
        // A repository the installation does not reach, or a commit GitHub does not know: no run, not a failure.
        if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.UnprocessableEntity) {
            return emptyList()
        }
        return decode<CheckRuns>(response, "check runs").checkRuns.map {
            val completedAt = instantOrNull(it.completedAt)
            GitHubCiRun(it.id, it.name, it.headSha, it.status, it.conclusion, it.app.slug, it.htmlUrl, completedAt)
        }
    }

    override suspend fun installationAccount(installationId: Long): String? {
        val response = send(HttpMethod.Get, "/app/installations/$installationId", appToken(jwt), null)
        if (response.status == HttpStatusCode.NotFound) return null
        return decode<Installation>(response, "installation").account.login
    }

    override suspend fun repositories(installationId: Long): List<GitHubRepo> {
        val token = installationToken(installationId, REPOS)
            ?: throw GitHubCallException("GitHub refused the installation token (422).")
        val repos = mutableListOf<GitHubRepo>()
        var page = 0
        do {
            page++
            val path = "/installation/repositories?per_page=$PER_PAGE&page=$page"
            val listed = decode<Repositories>(send(HttpMethod.Get, path, token, null), "repositories").repositories
            listed.mapTo(repos) {
                val pushedAt = instantOrNull(it.pushedAt)
                GitHubRepo(it.name, it.description, it.language, it.defaultBranch, it.archived, pushedAt)
            }
        } while (listed.size == PER_PAGE && page < MAX_PAGES)
        return repos
    }

    override suspend fun teams(installationId: Long, account: String): List<GitHubTeam> {
        // An App not allowed to read the members gets no token for them (422): it has no teams to show.
        val token = installationToken(installationId, TEAMS) ?: return emptyList()
        val teams = mutableListOf<GitHubTeam>()
        var after: String? = null
        var page = 0
        do {
            page++
            val query = buildJsonObject {
                put("query", TEAMS_QUERY)
                putJsonObject("variables") {
                    put("login", account)
                    put("after", after)
                }
            }
            val response = http.exchange(HttpMethod.Post, "$graphqlUrl/graphql", "/graphql") {
                headers(token)
                contentType(ContentType.Application.Json)
                setBody(query.toString())
            }
            val answer = decode<TeamsAnswer>(response, "teams")
            // A user's account is no organization: GitHub answers a null organization, with an error.
            val listed = answer.data?.organization?.teams
            listed?.let { found ->
                found.nodes.mapTo(teams) { team ->
                    val members = team.members.nodes.map { it.login }
                    GitHubTeam(team.slug, team.name, members, team.repositories.nodes.map { it.name })
                }
            }
            after = if (listed != null && listed.pageInfo.hasNextPage) listed.pageInfo.endCursor else null
        } while (after != null && page < MAX_PAGES)
        return teams
    }

    override suspend fun convertManifest(code: String): CreatedGitHubApp? {
        val response = send(HttpMethod.Post, "/app-manifests/$code/conversions", null, null)
        if (response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.UnprocessableEntity) {
            return null
        }
        val app = decode<Conversion>(response, "App")
        return CreatedGitHubApp(
            id = app.id,
            slug = app.slug,
            owner = app.owner?.login,
            privateKeyPem = app.pem,
            webhookSecret = app.webhookSecret,
            clientId = app.clientId,
            clientSecret = app.clientSecret,
        )
    }

    private suspend fun call(
        installationId: Long,
        method: HttpMethod,
        path: String,
        body: JsonObject?,
        timeoutMs: Long? = null,
    ): HttpResponse {
        val token = installationToken(installationId, CHECKS)
            ?: throw GitHubCallException("GitHub refused the installation token (422).")
        return send(method, path, token, body, timeoutMs)
    }

    // Null when GitHub refuses these permissions to the installation (422): the App was not granted them.
    private suspend fun installationToken(installationId: Long, permissions: Map<String, String>): String? =
        lock.withLock {
            val key = installationId to permissions
            tokens[key]?.takeIf { it.expiresAt - clock.now() > REFRESH_BEFORE }?.let { return it.token }
            val path = "/app/installations/$installationId/access_tokens"
            val response = send(HttpMethod.Post, path, appToken(jwt), tokenRequest(permissions))
            if (response.status == HttpStatusCode.UnprocessableEntity) return null
            val issued = decode<AccessToken>(response, "installation token")
            val token = InstallationToken(issued.token, Instant.parse(issued.expiresAt))
            tokens[key] = token
            token.token
        }

    private suspend fun send(
        method: HttpMethod,
        path: String,
        token: String?,
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
    private data class CheckRuns(@SerialName("check_runs") val checkRuns: List<CiRun>)

    @Serializable
    private data class CiRun(
        val id: Long,
        val name: String,
        @SerialName("head_sha") val headSha: String,
        val status: String,
        val conclusion: String? = null,
        val app: Slug,
        @SerialName("html_url") val htmlUrl: String? = null,
        @SerialName("completed_at") val completedAt: String? = null,
    )

    @Serializable
    private data class Slug(val slug: String)

    @Serializable
    private data class AccessToken(val token: String, @SerialName("expires_at") val expiresAt: String)

    @Serializable
    private data class Repository(
        val name: String,
        val description: String? = null,
        val language: String? = null,
        @SerialName("default_branch") val defaultBranch: String = "main",
        val archived: Boolean = false,
        @SerialName("pushed_at") val pushedAt: String? = null,
    )

    @Serializable
    private data class Repositories(val repositories: List<Repository>)

    @Serializable
    private data class TeamsAnswer(val data: TeamsData? = null)

    @Serializable
    private data class TeamsData(val organization: TeamsOrganization? = null)

    @Serializable
    private data class TeamsOrganization(val teams: TeamsPage)

    @Serializable
    private data class TeamsPage(val pageInfo: PageInfo, val nodes: List<TeamNode>)

    @Serializable
    private data class PageInfo(val hasNextPage: Boolean, val endCursor: String? = null)

    @Serializable
    private data class TeamNode(val slug: String, val name: String, val members: Logins, val repositories: Names)

    @Serializable
    private data class Logins(val nodes: List<Account>)

    @Serializable
    private data class Names(val nodes: List<Name>)

    @Serializable
    private data class Name(val name: String)

    @Serializable
    private data class Conversion(
        val id: Long,
        val slug: String,
        val owner: Account? = null,
        val pem: String,
        @SerialName("webhook_secret") val webhookSecret: String? = null,
        @SerialName("client_id") val clientId: String,
        @SerialName("client_secret") val clientSecret: String,
    )

    private companion object {
        const val HEAD_TIMEOUT_MS = 2_000L
        const val PER_PAGE = 100

        // 30 pages of 100: the repositories and teams of a very large organization; beyond, the first ones.
        const val MAX_PAGES = 30
        const val ENTERPRISE_REST = "/api/v3"
        const val TEAMS_QUERY = "query(\$login: String!, \$after: String) { organization(login: \$login) { " +
            "teams(first: 100, after: \$after) { pageInfo { hasNextPage endCursor } nodes { slug name " +
            "members(first: 100) { nodes { login } } repositories(first: 100) { nodes { name } } } } } }"
    }
}

/**
 * Reads a timestamp GitHub sent; a malformed one is dropped rather than failing the whole read.
 *
 * @param text The ISO 8601 text, or null.
 * @return The instant, or null.
 */
internal fun instantOrNull(text: String?): Instant? = text?.let {
    try {
        Instant.parse(it)
    } catch (_: IllegalArgumentException) {
        null
    }
}

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val REFRESH_BEFORE = 1.minutes
private val CHECKS = mapOf("checks" to "write", "pull_requests" to "read")
private val REPOS = mapOf("metadata" to "read")
private val TEAMS = mapOf("members" to "read", "metadata" to "read")
private val json = Json { ignoreUnknownKeys = true }

/**
 * [GitHubPeopleApi] over HTTPS: the code of the install's return is exchanged with the App's OAuth client for the
 * person's token, which only reads `GET /user/installations` and `GET /user`, then is dropped (never stored nor
 * logged).
 *
 * @param http The client (`expectSuccess = false`).
 * @param apiUrl GitHub's API.
 * @param webUrl GitHub's web address.
 * @param oauth The App's OAuth client, or null when it has none.
 */
class HttpGitHubPeople(
    private val http: HttpClient,
    private val apiUrl: String,
    private val webUrl: String,
    private val oauth: suspend () -> AppOAuthClient?,
) : GitHubPeopleApi {
    override suspend fun userToken(code: String): String? {
        val client = oauth() ?: throw GitHubCallException("The GitHub App has no OAuth client on this server.")
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
            val path = "/user/installations?per_page=$INSTALLATIONS_PAGE&page=$page"
            val response = http.exchange(HttpMethod.Get, "$apiUrl$path", path) { headers(userToken) }
            val batch = decode<UserInstallations>(response, "installations").installations
            batch.mapTo(ids) { it.id }
            page++
        } while (batch.size == INSTALLATIONS_PAGE && page <= INSTALLATIONS_PAGES)
        return ids
    }

    override suspend fun userLogin(userToken: String): String {
        val response = http.exchange(HttpMethod.Get, "$apiUrl$USER_PATH", USER_PATH) { headers(userToken) }
        return decode<UserLogin>(response, "person").login
    }

    @Serializable
    private data class UserToken(@SerialName("access_token") val accessToken: String? = null)

    @Serializable
    private data class InstallationId(val id: Long)

    @Serializable
    private data class UserLogin(val login: String)

    @Serializable
    private data class UserInstallations(val installations: List<InstallationId> = emptyList())
}

private const val USER_AGENT = "ostrom"
private const val OAUTH_TOKEN_PATH = "/login/oauth/access_token"
private const val USER_PATH = "/user"
private const val INSTALLATIONS_PAGE = 100

// 1,000 installations reachable by one person: beyond, the install is refused rather than read forever.
private const val INSTALLATIONS_PAGES = 10

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

private fun HttpRequestBuilder.headers(token: String?) {
    token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
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

// The path of a repository in GitHub's API, only for a repository's full name: a repository an agent declared never
// makes the installation's token call another route (AUDIT-SECU-2 B5).
private fun repoPath(repo: String): String =
    if (isRepositoryName(repo)) "/repos/$repo" else throw GitHubCallException("This is not a repository's full name.")

// The App's own JWT, for the calls made as the App rather than as an installation.
private suspend fun appToken(jwt: suspend () -> AppJwt?): String {
    val app = jwt() ?: throw GitHubCallException("The GitHub App is not configured on this server.")
    return app.token()
}

// The body asking GitHub for an installation token restricted to [permissions].
private fun tokenRequest(permissions: Map<String, String>): JsonObject = buildJsonObject {
    putJsonObject("permissions") {
        permissions.forEach { (permission, access) -> put(permission, access) }
    }
}
