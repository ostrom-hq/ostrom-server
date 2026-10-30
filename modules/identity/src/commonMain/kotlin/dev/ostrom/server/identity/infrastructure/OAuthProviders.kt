// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.infrastructure

import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.OAuthProvider
import dev.ostrom.server.identity.domain.ProviderRejectedException
import dev.ostrom.server.identity.domain.VerifiedIdentity
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Lenient JSON for provider answers: they add fields over time. */
internal val ProviderJson = Json { ignoreUnknownKeys = true }

/**
 * The credentials of an OAuth client registered with a provider.
 *
 * @property clientId The client id.
 * @property clientSecret The client secret (server side only, never sent to a browser).
 */
data class OAuthClient(val clientId: String, val clientSecret: String)

/**
 * Sign-in with GitHub (OAuth of the Ostrom GitHub App, with PKCE). GitHub gives no ID token: the account and its
 * verified e-mails come from the API with the user's access token, which is then dropped (Ostrom keeps no GitHub token
 * of the user).
 *
 * @param client The GitHub App's OAuth credentials.
 * @param http The HTTP client.
 * @param webUrl GitHub's web URL (overridden in tests).
 * @param apiUrl GitHub's API URL (overridden in tests).
 */
class GitHubOAuthProvider(
    private val client: OAuthClient,
    private val http: HttpClient,
    private val webUrl: String = "https://github.com",
    private val apiUrl: String = "https://api.github.com",
) : OAuthProvider {
    override val provider: IdentityProvider = IdentityProvider.GITHUB

    override fun authorizationUrl(
        state: String,
        codeChallenge: String,
        nonce: String,
        redirectUri: String,
        reauthenticate: Boolean,
    ): String =
        URLBuilder("$webUrl/login/oauth/authorize").apply {
            parameters.append("client_id", client.clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("scope", "read:user user:email")
            parameters.append("state", state)
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", "S256")
            parameters.append("allow_signup", "true")
            // GitHub has no max_age: the account picker at least stops a silent sign-in.
            if (reauthenticate) parameters.append("prompt", "select_account")
        }.buildString()

    override suspend fun identity(
        code: String,
        codeVerifier: String,
        nonce: String,
        redirectUri: String,
    ): VerifiedIdentity {
        val token = providerCall {
            http.submitForm(
                "$webUrl/login/oauth/access_token",
                parameters {
                    append("client_id", client.clientId)
                    append("client_secret", client.clientSecret)
                    append("code", code)
                    append("redirect_uri", redirectUri)
                    append("code_verifier", codeVerifier)
                },
            ) { header(HttpHeaders.Accept, "application/json") }.decode<GitHubToken>()
        }
        val accessToken = token.accessToken ?: throw ProviderRejectedException(token.error ?: "no access token.")
        val user = providerCall { api("/user", accessToken).decode<GitHubUser>() }
        val emails = providerCall { api("/user/emails", accessToken).decode<List<GitHubEmail>>() }
        // The primary address if GitHub verified it, else any verified one: only a verified address merges accounts.
        val verified = emails.filter { it.verified }.sortedByDescending { it.primary }.firstOrNull()?.email
        return VerifiedIdentity(
            provider = IdentityProvider.GITHUB,
            subject = user.id.toString(),
            email = verified ?: user.email,
            emailVerified = verified != null,
            displayName = user.name?.takeIf { it.isNotBlank() } ?: user.login,
            avatarUrl = user.avatarUrl,
            login = user.login,
        )
    }

    private suspend fun api(path: String, accessToken: String): HttpResponse = http.get("$apiUrl$path") {
        header(HttpHeaders.Authorization, "Bearer $accessToken")
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    @Serializable
    private data class GitHubToken(
        @SerialName("access_token") val accessToken: String? = null,
        val error: String? = null,
    )

    @Serializable
    private data class GitHubUser(
        val id: Long,
        val login: String,
        val name: String? = null,
        val email: String? = null,
        @SerialName("avatar_url") val avatarUrl: String? = null,
    )

    @Serializable
    private data class GitHubEmail(val email: String, val primary: Boolean = false, val verified: Boolean = false)
}

/**
 * Where an OpenID Connect provider lives and how it is reached.
 *
 * @property issuers The `iss` values its ID tokens may carry (Google uses two spellings).
 * @property authorizationEndpoint The consent page.
 * @property tokenEndpoint The code exchange.
 * @property extraParameters Added to the consent page URL (`prompt=select_account` for Google).
 */
data class OidcEndpoints(
    val issuers: Set<String>,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val extraParameters: Map<String, String> = emptyMap(),
) {
    /** The endpoints of the well-known providers. */
    companion object {
        /** Google's endpoints. */
        val GOOGLE = OidcEndpoints(
            issuers = setOf("https://accounts.google.com", "accounts.google.com"),
            authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenEndpoint = "https://oauth2.googleapis.com/token",
        )
    }
}

/**
 * Sign-in with an OpenID Connect provider by the authorization code flow, with PKCE and a nonce: Google, and the SSO of
 * an organization (Okta, Entra ID, Google Workspace).
 *
 * The ID token comes straight from the token endpoint, over TLS, in exchange for a one-time code bound to the PKCE
 * verifier: OpenID Connect Core § 3.1.3.7 allows the TLS server validation to replace the signature check in that
 * case. Its issuer, audience, expiry and nonce are checked here.
 *
 * @param provider [IdentityProvider.GOOGLE] or [IdentityProvider.OIDC].
 * @param endpoints The provider's endpoints.
 * @param client The OAuth client.
 * @param http The HTTP client.
 * @param clock The clock, for the token's expiry.
 * @param connectionId For an organization's SSO, its connection; null for Google.
 */
class OidcProvider(
    override val provider: IdentityProvider,
    private val endpoints: OidcEndpoints,
    private val client: OAuthClient,
    private val http: HttpClient,
    private val clock: Clock,
    private val connectionId: Uuid? = null,
) : OAuthProvider {
    override fun authorizationUrl(
        state: String,
        codeChallenge: String,
        nonce: String,
        redirectUri: String,
        reauthenticate: Boolean,
    ): String =
        URLBuilder(endpoints.authorizationEndpoint).apply {
            parameters.append("response_type", "code")
            parameters.append("client_id", client.clientId)
            parameters.append("redirect_uri", redirectUri)
            parameters.append("scope", "openid email profile")
            parameters.append("state", state)
            parameters.append("nonce", nonce)
            parameters.append("code_challenge", codeChallenge)
            parameters.append("code_challenge_method", "S256")
            endpoints.extraParameters.forEach { (name, value) -> parameters.append(name, value) }
            // OpenID Connect: a sign-in older than this is not reused, the provider asks again.
            if (reauthenticate) parameters.append("max_age", REAUTHENTICATION_MAX_AGE_SECONDS.toString())
        }.buildString()

    override suspend fun identity(
        code: String,
        codeVerifier: String,
        nonce: String,
        redirectUri: String,
    ): VerifiedIdentity {
        val token = providerCall {
            http.submitForm(
                endpoints.tokenEndpoint,
                parameters {
                    append("grant_type", "authorization_code")
                    append("code", code)
                    append("redirect_uri", redirectUri)
                    append("client_id", client.clientId)
                    append("client_secret", client.clientSecret)
                    append("code_verifier", codeVerifier)
                },
            ) { header(HttpHeaders.Accept, "application/json") }.decode<TokenResponse>()
        }
        val idToken = token.idToken ?: throw ProviderRejectedException(token.error ?: "no ID token.")
        val claims = parseClaims(idToken)
        when {
            claims.iss !in endpoints.issuers -> throw ProviderRejectedException("unexpected issuer.")
            !claims.audiences.contains(client.clientId) -> throw ProviderRejectedException("token for another client.")
            claims.azp != null && claims.azp != client.clientId ->
                throw ProviderRejectedException("token for another client.")
            claims.exp == null || Instant.fromEpochSeconds(claims.exp) <= clock.now() ->
                throw ProviderRejectedException("expired token.")
            claims.nonce != nonce -> throw ProviderRejectedException("nonce mismatch.")
        }
        val subject = claims.sub
        if (subject.isNullOrEmpty()) throw ProviderRejectedException("no subject.")
        return VerifiedIdentity(
            provider = provider,
            subject = subject,
            email = claims.email,
            emailVerified = claims.email != null && claims.emailVerified == true,
            displayName = claims.name,
            avatarUrl = claims.picture,
            connectionId = connectionId,
            hostedDomain = claims.hd,
        )
    }

    private fun parseClaims(idToken: String): IdTokenClaims {
        val parts = idToken.split('.')
        // A wrong number of parts, bad base64 and bad JSON (a SerializationException) are all IllegalArgumentException.
        return try {
            require(parts.size == JWT_PARTS) { "Not a JWT." }
            val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
            val payload = base64.decode(parts[1]).decodeToString()
            ProviderJson.decodeFromString<IdTokenClaims>(payload)
        } catch (_: IllegalArgumentException) {
            throw ProviderRejectedException("malformed ID token.")
        }
    }

    @Serializable
    private data class TokenResponse(
        @SerialName("id_token") val idToken: String? = null,
        val error: String? = null,
    )

    @Serializable
    private data class IdTokenClaims(
        val iss: String? = null,
        val sub: String? = null,
        val aud: JsonElement? = null,
        val azp: String? = null,
        val exp: Long? = null,
        val nonce: String? = null,
        val email: String? = null,
        @SerialName("email_verified") val emailVerified: Boolean? = null,
        val name: String? = null,
        val picture: String? = null,
        val hd: String? = null,
    ) {
        /** `aud` is a string or an array of strings. */
        val audiences: List<String>
            get() = when (val value = aud) {
                is JsonPrimitive -> listOfNotNull(value.content.takeIf { value.isString })
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.content }
                else -> emptyList()
            }
    }

    private companion object {
        const val JWT_PARTS = 3

        /** A sign-in again before a yes: the provider reuses none older than the five minutes of S2-54. */
        const val REAUTHENTICATION_MAX_AGE_SECONDS = 300
    }
}

/**
 * Runs a call to a provider: any network failure, refusal or unreadable answer becomes [ProviderRejectedException], a
 * sign-in error the user can retry; cancellation still propagates.
 */
internal suspend fun <T> providerCall(block: suspend () -> T): T = try {
    block()
} catch (error: CancellationException) {
    throw error
} catch (error: ProviderRejectedException) {
    throw error
} catch (_: Exception) {
    throw ProviderRejectedException("the provider could not be reached or answered something unexpected.")
}

/** Decodes a provider's JSON answer, refusing an error status. */
internal suspend inline fun <reified T> HttpResponse.decode(): T {
    val body = bodyAsText()
    if (!status.isSuccess()) throw ProviderRejectedException("HTTP ${status.value}.")
    return ProviderJson.decodeFromString<T>(body)
}

/**
 * Closes the module's HTTP client when Koin stops (its `onClose`, which may hand over null).
 *
 * @param client The client, if it was created.
 */
internal fun closeHttpClient(client: HttpClient?) {
    client?.close()
}
