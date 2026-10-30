// SPDX-License-Identifier: AGPL-3.0-only

package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ProviderRejectedException
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.fakes.SettableClock
import dev.ostrom.server.identity.infrastructure.GitHubOAuthProvider
import dev.ostrom.server.identity.infrastructure.OAuthClient
import dev.ostrom.server.identity.infrastructure.OidcEndpoints
import dev.ostrom.server.identity.infrastructure.OidcProvider
import dev.ostrom.server.identity.infrastructure.closeHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.http.parseQueryString
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Instant
import kotlin.uuid.Uuid

class OAuthProvidersTest {
    private val client = OAuthClient("client-id", "client-secret")
    private val requests = mutableListOf<HttpRequestData>()

    private fun http(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(
        MockEngine { request ->
            requests += request
            handler(request)
        },
    ) { expectSuccess = false }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

    private fun form(request: HttpRequestData): Map<String, String> {
        val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
        return parseQueryString(body).entries().associate { it.key to it.value.single() }
    }

    private fun query(url: String): Map<String, String> =
        Url(url).parameters.entries().associate { it.key to it.value.single() }

    private fun github(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        GitHubOAuthProvider(client, http(handler), "https://gh.test", "https://api.gh.test")

    @Test
    fun gitHubSendsTheConsentPageWithPkce() {
        val url = github { error("no call") }.authorizationUrl("st", "ch", "unused", "https://api.test/cb", false)

        assertEquals("https://gh.test/login/oauth/authorize", url.substringBefore('?'))
        assertEquals(
            mapOf(
                "client_id" to "client-id", "redirect_uri" to "https://api.test/cb", "scope" to "read:user user:email",
                "state" to "st", "code_challenge" to "ch", "code_challenge_method" to "S256", "allow_signup" to "true",
            ),
            query(url),
        )
    }

    @Test
    fun signingInAgainAsksTheProviderNotToReuseAnOldSignIn() {
        val gitHub = github { error("no call") }.authorizationUrl("st", "ch", "unused", "https://api.test/cb", true)
        val openId = oidc(null).authorizationUrl("st", "ch", "n-1", "https://api.test/cb", true)

        assertEquals("select_account", query(gitHub)["prompt"])
        assertEquals("300", query(openId)["max_age"])
    }

    @Test
    fun gitHubGivesTheAccountWithItsPrimaryVerifiedEmail() = runTest {
        val provider = github { request ->
            when (request.url.encodedPath) {
                "/login/oauth/access_token" -> json("""{"access_token":"gho_x","token_type":"bearer"}""")
                "/user" -> json(
                    """{"id":583231,"login":"ada","name":"Ada Lovelace","email":"public@example.com",""" +
                        """"avatar_url":"https://a.test/1"}""",
                )
                else -> json(
                    """[{"email":"old@example.com","primary":false,"verified":true},""" +
                        """{"email":"ada@example.com","primary":true,"verified":true},""" +
                        """{"email":"spam@example.com","primary":false,"verified":false}]""",
                )
            }
        }

        val identity = provider.identity("code", "verifier", "unused", "https://api.test/cb")

        assertEquals(
            VerifiedIdentity(
                IdentityProvider.GITHUB, "583231", "ada@example.com", true, "Ada Lovelace", "https://a.test/1", "ada",
            ),
            identity,
        )
        assertEquals(
            mapOf(
                "client_id" to "client-id", "client_secret" to "client-secret", "code" to "code",
                "redirect_uri" to "https://api.test/cb", "code_verifier" to "verifier",
            ),
            form(requests[0]),
        )
        assertEquals("application/json", requests[0].headers[HttpHeaders.Accept])
        assertEquals("Bearer gho_x", requests[1].headers[HttpHeaders.Authorization])
        assertEquals("https://api.gh.test/user/emails", requests[2].url.toString())
    }

    @Test
    fun gitHubWithoutVerifiedEmailGivesAnUnverifiedOneAndTheLogin() = runTest {
        val provider = github { request ->
            when (request.url.encodedPath) {
                "/login/oauth/access_token" -> json("""{"access_token":"gho_x"}""")
                "/user" -> json("""{"id":1,"login":"ada","name":" ","email":"public@example.com"}""")
                else -> json("""[{"email":"x@example.com","verified":false}]""")
            }
        }

        val identity = provider.identity("code", "v", "n", "r")

        assertEquals(
            VerifiedIdentity(IdentityProvider.GITHUB, "1", "public@example.com", false, "ada", null, "ada"),
            identity,
        )
    }

    @Test
    fun gitHubWithoutANameShowsTheLogin() = runTest {
        val provider = github { request ->
            when (request.url.encodedPath) {
                "/login/oauth/access_token" -> json("""{"access_token":"gho_x"}""")
                "/user" -> json("""{"id":2,"login":"bob"}""")
                else -> json("[]")
            }
        }

        assertEquals("bob", provider.identity("code", "v", "n", "r").displayName)
    }

    @Test
    fun gitHubRefusalsBecomeSignInErrors() = runTest {
        assertEquals(
            "The provider refused the sign-in: bad_verification_code",
            assertFailsWith<ProviderRejectedException> {
                github { json("""{"error":"bad_verification_code"}""") }.identity("c", "v", "n", "r")
            }.message,
        )
        assertFailsWith<ProviderRejectedException> { github { json("{}") }.identity("c", "v", "n", "r") }
        assertFailsWith<ProviderRejectedException> {
            github { json("{}", HttpStatusCode.InternalServerError) }.identity("c", "v", "n", "r")
        }
        assertFailsWith<ProviderRejectedException> { github { json("not json") }.identity("c", "v", "n", "r") }
        assertFailsWith<ProviderRejectedException> { github { error("network down") }.identity("c", "v", "n", "r") }
    }

    private val clock = SettableClock(Instant.parse("2026-10-30T10:00:00Z"))
    private val endpoints =
        OidcEndpoints(setOf("https://idp.test"), "https://idp.test/authorize", "https://idp.test/token")

    private fun jwt(claims: String) = "eyJhbGciOiJSUzI1NiJ9." +
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(claims.encodeToByteArray()) + ".c2ln"

    private fun claims(
        iss: String = "\"https://idp.test\"",
        aud: String = "\"client-id\"",
        exp: String = "1793440000",
        nonce: String = "\"n-1\"",
        extra: String = ""
    ) = """{"iss":$iss,"aud":$aud,"exp":$exp,"nonce":$nonce,"sub":"108",""" +
        """"email":"ada@example.com","email_verified":true,""" +
        """"name":"Ada","picture":"https://p.test/a"$extra}"""

    private fun oidc(idToken: String?, connection: Uuid? = null): OidcProvider {
        val body = if (idToken == null) {
            """{"error":"invalid_grant"}"""
        } else {
            """{"id_token":"$idToken","access_token":"a"}"""
        }
        return OidcProvider(
            IdentityProvider.OIDC, endpoints.copy(extraParameters = mapOf("prompt" to "login")), client,
            http { json(body) }, clock, connection,
        )
    }

    @Test
    fun oidcSendsTheConsentPageWithPkceAndNonce() {
        val url = oidc(null).authorizationUrl("st", "ch", "n-1", "https://api.test/cb", false)

        assertEquals("https://idp.test/authorize", url.substringBefore('?'))
        assertEquals(
            mapOf(
                "response_type" to "code", "client_id" to "client-id", "redirect_uri" to "https://api.test/cb",
                "scope" to "openid email profile", "state" to "st", "nonce" to "n-1", "code_challenge" to "ch",
                "code_challenge_method" to "S256", "prompt" to "login",
            ),
            query(url),
        )
    }

    @Test
    fun oidcChecksTheIdTokenAndGivesTheIdentity() = runTest {
        val connection = Uuid.random()
        val withAzp = claims(aud = """["other","client-id"]""", extra = ""","azp":"client-id"""")
        val identity = oidc(jwt(withAzp), connection)
            .identity("code", "verifier", "n-1", "https://api.test/cb")

        assertEquals(
            VerifiedIdentity(
                IdentityProvider.OIDC, "108", "ada@example.com", true, "Ada", "https://p.test/a",
                connectionId = connection,
            ),
            identity,
        )
        assertEquals(
            mapOf(
                "grant_type" to "authorization_code", "code" to "code", "redirect_uri" to "https://api.test/cb",
                "client_id" to "client-id", "client_secret" to "client-secret", "code_verifier" to "verifier",
            ),
            form(requests.single()),
        )
    }

    @Test
    fun oidcWithAnUnverifiedEmailCannotMergeAccounts() = runTest {
        val unverified =
            """{"iss":"https://idp.test","aud":"client-id","exp":1793440000,"nonce":"n-1","sub":"108",""" +
                """"email":"a@b.co"}"""

        val identity = oidc(jwt(unverified)).identity("c", "v", "n-1", "r")

        assertEquals(VerifiedIdentity(IdentityProvider.OIDC, "108", "a@b.co", false), identity)
        val noEmail =
            """{"iss":"https://idp.test","aud":"client-id","exp":1793440000,"nonce":"n-1","sub":"108",""" +
                """"email_verified":true}"""
        assertEquals(false, oidc(jwt(noEmail)).identity("c", "v", "n-1", "r").emailVerified)
    }

    @Test
    fun oidcRefusesATokenThatIsNotForThisSignIn() = runTest {
        val refused = listOf(
            jwt(claims(iss = "\"https://evil.test\"")),
            jwt(claims(iss = "null")),
            jwt(claims(aud = "\"other\"")),
            jwt(claims(aud = "[\"other\"]")),
            jwt(claims(aud = "42")),
            jwt(claims(aud = "{}")),
            jwt(claims(extra = ""","azp":"other"""")),
            jwt(claims(exp = "1793354400")),
            jwt(claims(exp = "null")),
            jwt(claims(nonce = "\"n-2\"")),
            jwt(claims().replace("\"sub\":\"108\"", "\"sub\":\"\"")),
            jwt(claims().replace("\"sub\":\"108\",", "")),
            "only.two",
            "a.%%%.c",
            jwt("not json"),
        )
        // 1793354400 is the clock's time (2026-10-30T10:00:00Z): a token expiring now is expired.
        refused.forEachIndexed { index, token ->
            assertFailsWith<ProviderRejectedException>("token $index") { oidc(token).identity("c", "v", "n-1", "r") }
        }
        assertEquals(
            "The provider refused the sign-in: invalid_grant",
            assertFailsWith<ProviderRejectedException> { oidc(null).identity("c", "v", "n-1", "r") }.message,
        )
        val noToken = OidcProvider(IdentityProvider.GOOGLE, endpoints, client, http { json("{}") }, clock)
        assertFailsWith<ProviderRejectedException> { noToken.identity("c", "v", "n-1", "r") }
    }

    @Test
    fun googleHasItsEndpoints() {
        assertEquals(setOf("https://accounts.google.com", "accounts.google.com"), OidcEndpoints.GOOGLE.issuers)
        assertEquals("https://oauth2.googleapis.com/token", OidcEndpoints.GOOGLE.tokenEndpoint)
    }

    @Test
    fun theModuleClosesItsHttpClientAndToleratesNone() {
        val client = http { json("{}") }
        closeHttpClient(client)
        assertFalse(client.isActive)
        closeHttpClient(null)
    }
}
