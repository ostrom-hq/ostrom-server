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
 * Every client this instance knows is asked in turn (the `github-sign-in` settings', then the GitHub App's): a token
 * issued by either client checks, whichever one sign-in uses now.
 *
 * @param github Reads GitHub's sign-ins as set now (each OAuth client and its GitHub's API URL), empty while none is.
 * @param http The HTTP client.
 */
class GitHubTokenVerifierImpl(
    private val github: suspend () -> List<GitHubSignIn>,
    private val http: HttpClient,
) : GitHubTokenVerifier {
    override suspend fun verify(token: String): GitHubAccount? =
        github().ifEmpty { throw ProviderNotConfiguredException() }.firstNotNullOfOrNull { check(it, token) }

    private suspend fun check(signIn: GitHubSignIn, token: String): GitHubAccount? {
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
