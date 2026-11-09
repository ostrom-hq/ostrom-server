// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.GitHubAccount
import run.ostrom.server.identity.domain.GitHubTokenVerifier
import run.ostrom.server.identity.domain.ProviderNotConfiguredException
import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * [GitHubTokenVerifier] on GitHub's "check a token" API (`POST /applications/{client_id}/token`, authenticated with
 * the client id and secret of Ostrom's GitHub app). GitHub answers 404 for a token it did not issue to that app (a
 * token of another OAuth app, a personal access token, a revoked one), and otherwise names the account it belongs to.
 *
 * @param client The GitHub app's OAuth credentials, or null when this instance has none.
 * @param http The HTTP client.
 * @param apiUrl GitHub's API URL (overridden in tests by a fake GitHub).
 */
class GitHubTokenVerifierImpl(
    private val client: OAuthClient?,
    private val http: HttpClient,
    private val apiUrl: String = "https://api.github.com",
) : GitHubTokenVerifier {
    override suspend fun verify(token: String): GitHubAccount? {
        val app = client ?: throw ProviderNotConfiguredException()
        val body = ProviderJson.encodeToString(CheckRequest.serializer(), CheckRequest(token))
        val response = providerCall {
            http.post("$apiUrl/applications/${app.clientId.encodeURLPathPart()}/token") {
                basicAuth(app.clientId, app.clientSecret)
                header(HttpHeaders.Accept, "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                setBody(TextContent(body, ContentType.Application.Json))
            }
        }
        if (response.status == HttpStatusCode.NotFound) return null
        val checked = providerCall { response.decode<CheckedToken>() }
        return GitHubAccount(checked.user.id, checked.user.login)
    }

    @Serializable
    private data class CheckRequest(@SerialName("access_token") val accessToken: String)

    @Serializable
    private data class CheckedToken(val user: Owner)

    @Serializable
    private data class Owner(val id: Long, val login: String)
}
