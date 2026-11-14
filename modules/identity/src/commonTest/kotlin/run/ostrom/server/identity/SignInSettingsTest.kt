// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity

import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.SignUpMode
import run.ostrom.server.identity.infrastructure.GITHUB_SIGN_IN_SETTINGS
import run.ostrom.server.identity.infrastructure.GOOGLE_SIGN_IN_SETTINGS
import run.ostrom.server.identity.infrastructure.GitHubSignIn
import run.ostrom.server.identity.infrastructure.GoogleSignIn
import run.ostrom.server.identity.infrastructure.InstanceSignInMethods
import run.ostrom.server.identity.infrastructure.OAuthClient
import run.ostrom.server.identity.infrastructure.OidcEndpoints
import run.ostrom.server.identity.infrastructure.SIGN_UP_SETTINGS
import run.ostrom.server.identity.infrastructure.SignInSettings
import run.ostrom.server.testing.TestInstanceSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** The sign-in providers and the sign-up mode an administrator sets in the console (Admin → Settings). */
class SignInSettingsTest {
    private val stored = TestInstanceSettings()
    private val settings = SignInSettings(stored)

    @Test
    fun aProviderIsUnsetUntilItsClientIdAndSecretAre() = runTest {
        assertNull(settings.github())
        assertNull(settings.google())
        stored.save("github-sign-in", mapOf("clientId" to "Iv1.a"))
        stored.save("google", mapOf("clientId" to " ", "clientSecret" to "s"))
        assertNull(settings.github())
        assertNull(settings.google())
        stored.save("github-sign-in", mapOf("clientSecret" to "s"))
        assertNull(settings.github())
    }

    @Test
    fun gitHubIsGitHubComUnlessItsUrlsAreSet() = runTest {
        stored.save("github-sign-in", mapOf("clientId" to " Iv1.a ", "clientSecret" to "s1"))
        assertEquals(
            GitHubSignIn(OAuthClient("Iv1.a", "s1"), "https://github.com", "https://api.github.com"),
            settings.github(),
        )
        stored.save(
            "github-sign-in",
            mapOf(
                "clientId" to "Iv1.a", "clientSecret" to "s1",
                "webUrl" to "http://fake:9000/", "apiUrl" to "http://fake:9000/api/",
            ),
        )
        val fake = GitHubSignIn(OAuthClient("Iv1.a", "s1"), "http://fake:9000", "http://fake:9000/api")
        assertEquals(fake, settings.github())
    }

    @Test
    fun googlesEndpointsAreReplacedOneByOne() = runTest {
        stored.save("google", mapOf("clientId" to "g", "clientSecret" to "s2"))
        assertEquals(GoogleSignIn(OAuthClient("g", "s2"), OidcEndpoints.GOOGLE), settings.google())
        stored.save(
            "google",
            mapOf(
                "clientId" to "g", "clientSecret" to "s2", "issuer" to "http://fake:9001",
                "authorizationUrl" to "http://fake:9001/auth", "tokenUrl" to "http://fake:9001/token",
            ),
        )
        val fake = OidcEndpoints(setOf("http://fake:9001"), "http://fake:9001/auth", "http://fake:9001/token")
        assertEquals(GoogleSignIn(OAuthClient("g", "s2"), fake), settings.google())
    }

    @Test
    fun signUpIsInviteOnlyUnlessAnAdministratorOpensIt() = runTest {
        assertEquals(SignUpMode.INVITE, settings.signUp())
        stored.save("sign-up", mapOf("mode" to "open"))
        assertEquals(SignUpMode.OPEN, settings.signUp())
        stored.save("sign-up", mapOf("mode" to "everyone"))
        assertEquals(SignUpMode.INVITE, settings.signUp())
    }

    @Test
    fun theSectionsNameTheirFormerVariablesAndRefuseWhatCannotWork() {
        val google = GOOGLE_SIGN_IN_SETTINGS.fields.associate { it.name to it.legacyVariable }
        assertEquals("OSTROM_GOOGLE_TOKEN_URL", google["tokenUrl"])
        assertEquals(
            listOf(
                "OSTROM_GITHUB_CLIENT_ID", "OSTROM_GITHUB_CLIENT_SECRET", "OSTROM_GITHUB_WEB_URL", "OSTROM_GITHUB_API_URL",
            ),
            GITHUB_SIGN_IN_SETTINGS.fields.map { it.legacyVariable },
        )
        assertTrue(GITHUB_SIGN_IN_SETTINGS.field("clientSecret")!!.secret)
        assertTrue(GOOGLE_SIGN_IN_SETTINGS.field("clientSecret")!!.secret)
        val url = GITHUB_SIGN_IN_SETTINGS.field("apiUrl")!!
        assertNull(url.check("https://ghe.example/api/v3"))
        assertNull(url.check("http://127.0.0.1:4610"))
        assertEquals("an http:// or https:// URL.", url.check("ghe.example"))
        val mode = SIGN_UP_SETTINGS.field("mode")!!
        assertEquals("OSTROM_SIGNUP", mode.legacyVariable)
        assertNull(mode.check("Open"))
        assertEquals("invite or open.", mode.check("everyone"))
    }

    @Test
    fun theOfferedProvidersFollowTheSettingsAndGoogleLetsThePersonPickAnAccount() = runTest {
        var mailing = false
        val methods = InstanceSignInMethods(settings, HttpClient(MockEngine { error("unused") }), Clock.System) {
            mailing
        }
        assertEquals(emptyList(), methods.current().keys)
        stored.save("google", mapOf("clientId" to "g", "clientSecret" to "s2"))
        stored.save("github-sign-in", mapOf("clientId" to "Iv1.a", "clientSecret" to "s1"))
        mailing = true

        val offered = methods.current()
        assertEquals(listOf("github", "google", "email"), offered.keys)
        val google = Url(offered.oauth.getValue(IdentityProvider.GOOGLE).authorizationUrl("s", "c", "n", "cb", false))
        assertEquals("select_account", google.parameters["prompt"])
        assertEquals("g", google.parameters["client_id"])
        val github = Url(offered.oauth.getValue(IdentityProvider.GITHUB).authorizationUrl("s", "c", "n", "cb", false))
        assertEquals("github.com", github.host)
        assertFalse(github.parameters.contains("prompt"))
    }
}
