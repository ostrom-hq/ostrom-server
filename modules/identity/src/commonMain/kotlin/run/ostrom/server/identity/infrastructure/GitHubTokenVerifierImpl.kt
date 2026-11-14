// SPDX-License-Identifier: Apache-2.0
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
 * @param github Reads GitHub's sign-in as set in the console now (the app's OAuth credentials and GitHub's API URL),
 *   null while it is not.
 * @param http The HTTP client.
 */
class GitHubTokenVerifierImpl(
    private val github: suspend () -> GitHubSignIn?,
    private val http: HttpClient,
) : GitHubTokenVerifier {
    override suspend fun verify(token: String): GitHubAccount? {
        val signIn = github() ?: throw ProviderNotConfiguredException()
        val app = signIn.client
        val apiUrl = signIn.apiUrl
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
