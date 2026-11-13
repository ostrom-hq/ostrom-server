// SPDX-License-Identifier: AGPL-3.0-only

package run.ostrom.server.identity

import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.Mail
import run.ostrom.server.identity.domain.MailNotSentException
import run.ostrom.server.identity.fakes.SettableClock
import run.ostrom.server.identity.infrastructure.DirectoryMailSender
import run.ostrom.server.identity.infrastructure.OAuthClient
import run.ostrom.server.identity.infrastructure.OidcEndpoints
import run.ostrom.server.identity.infrastructure.ResendMailSender
import run.ostrom.server.identity.infrastructure.UnconfiguredMailSender
import run.ostrom.server.identity.infrastructure.magicLinkMail
import run.ostrom.server.identity.presentation.IdentityConfig
import run.ostrom.server.identity.presentation.SignInConfig
import run.ostrom.server.kernel.config.InvalidConfigException
import run.ostrom.server.kernel.config.MapConfigSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class MailAndConfigTest {
    private val mail = Mail("ada@example.com", "Sign in to Ostrom", "<p>hi</p>", "hi")

    @Test
    fun resendReceivesTheMailWithTheKey() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { requests += it; respond("""{"id":"e1"}""") })

        ResendMailSender(http, "re_key", "Ostrom <login@mail.ostrom.run>", "https://resend.test").send(mail)

        val request = requests.single()
        assertEquals("https://resend.test/emails", request.url.toString())
        assertEquals("Bearer re_key", request.headers[HttpHeaders.Authorization])
        assertEquals(
            """{"from":"Ostrom <login@mail.ostrom.run>","to":["ada@example.com"],"subject":"Sign in to Ostrom",""" +
                """"html":"<p>hi</p>","text":"hi"}""",
            (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString(),
        )
    }

    @Test
    fun aResendFailureIsAMailNotSent() = runTest {
        val refused =
            HttpClient(MockEngine { respond("{}", HttpStatusCode.UnprocessableEntity) }) { expectSuccess = false }
        assertFailsWith<MailNotSentException> { ResendMailSender(refused, "k", "f").send(mail) }

        val down = HttpClient(MockEngine { error("network down") })
        assertFailsWith<MailNotSentException> { ResendMailSender(down, "k", "f").send(mail) }
        assertFailsWith<MailNotSentException> { UnconfiguredMailSender().send(mail) }
    }

    @Test
    fun aDirectoryReceivesOneFilePerMail() = runTest {
        val directory = Path(SystemTemporaryDirectory, "ostrom-mail-${Uuid.random()}")
        DirectoryMailSender(directory.toString(), SettableClock()).send(mail.copy(to = "Ada+x@example.com"))

        val file = SystemFileSystem.list(directory).single()
        assertEquals("1793354400000-ada_x@example.com.txt", file.name)
        assertEquals(
            "To: Ada+x@example.com\nSubject: Sign in to Ostrom\n\nhi\n",
            SystemFileSystem.source(file).buffered().use { it.readString() },
        )
        SystemFileSystem.delete(file)
        SystemFileSystem.delete(directory)
    }

    @Test
    fun anUnwritableDirectoryIsAMailNotSent() = runTest {
        val blocker = Path(SystemTemporaryDirectory, "ostrom-mail-file-${Uuid.random()}")
        SystemFileSystem.sink(blocker).buffered().use { it.writeString("a file, not a directory") }

        assertFailsWith<MailNotSentException> { DirectoryMailSender(blocker.toString(), SettableClock()).send(mail) }
        SystemFileSystem.delete(blocker)
    }

    @Test
    fun theSignInMailCarriesTheLinkEscaped() {
        val built = magicLinkMail("ada<x>@example.com", "https://app.test/auth/email#token=a&b=\"c'")

        assertEquals("Sign in to Ostrom", built.subject)
        assertTrue("href=\"https://app.test/auth/email#token=a&amp;b=&quot;c&#39;\"" in built.html, built.html)
        assertTrue("ada&lt;x&gt;@example.com" in built.html)
        assertTrue("It works once, for 15 minutes." in built.html)
        assertFalse("<img" in built.html)
        assertTrue(built.text.contains("https://app.test/auth/email#token=a&b=\"c'"))
    }

    @Test
    fun withoutCredentialsNoMethodIsConfigured() {
        val config = SignInConfig.from(MapConfigSource(emptyMap()))

        assertNull(config.github)
        assertNull(config.google)
        assertNull(config.resendApiKey)
        assertNull(config.mailDirectory)
        assertEquals("https://github.com", config.githubWebUrl)
        assertEquals("https://api.github.com", config.githubApiUrl)
        assertEquals(OidcEndpoints.GOOGLE, config.googleEndpoints)
        assertEquals("Ostrom <login@mail.ostrom.run>", config.mailFrom)
    }

    @Test
    fun credentialsAndFakeEndpointsAreRead() {
        val config = SignInConfig.from(
            MapConfigSource(
                mapOf(
                    "OSTROM_GITHUB_CLIENT_ID" to " Iv1.a ", "OSTROM_GITHUB_CLIENT_SECRET" to "s1",
                    "OSTROM_GITHUB_WEB_URL" to "http://fake:9000/", "OSTROM_GITHUB_API_URL" to "http://fake:9000/api/",
                    "OSTROM_GOOGLE_CLIENT_ID" to "g", "OSTROM_GOOGLE_CLIENT_SECRET" to "s2",
                    "OSTROM_GOOGLE_ISSUER" to "http://fake:9001",
                    "OSTROM_GOOGLE_AUTHORIZATION_URL" to "http://fake:9001/auth",
                    "OSTROM_GOOGLE_TOKEN_URL" to "http://fake:9001/token",
                    "OSTROM_RESEND_API_KEY" to "re_x",
                    "OSTROM_MAIL_FROM" to "Me <me@x.test>",
                    "OSTROM_MAIL_DIR" to "/tmp/m",
                ),
            ),
        )

        assertEquals(OAuthClient("Iv1.a", "s1"), config.github)
        assertEquals("http://fake:9000", config.githubWebUrl)
        assertEquals("http://fake:9000/api", config.githubApiUrl)
        assertEquals(OAuthClient("g", "s2"), config.google)
        assertEquals(
            OidcEndpoints(setOf("http://fake:9001"), "http://fake:9001/auth", "http://fake:9001/token"),
            config.googleEndpoints,
        )
        assertEquals("re_x", config.resendApiKey)
        assertEquals("Me <me@x.test>", config.mailFrom)
        assertEquals("/tmp/m", config.mailDirectory)
    }

    @Test
    fun halfConfiguredMethodsRefuseToStart() {
        listOf(
            mapOf("OSTROM_GITHUB_CLIENT_ID" to "a"),
            mapOf("OSTROM_GOOGLE_CLIENT_SECRET" to "a"),
            mapOf("OSTROM_GOOGLE_ISSUER" to "http://x"),
            mapOf("OSTROM_GOOGLE_ISSUER" to "http://x", "OSTROM_GOOGLE_AUTHORIZATION_URL" to "http://x/a"),
            mapOf("OSTROM_GOOGLE_AUTHORIZATION_URL" to "http://x/a"),
            mapOf("OSTROM_GOOGLE_TOKEN_URL" to "http://x/t"),
        ).forEach { assertFailsWith<InvalidConfigException>(it.toString()) { SignInConfig.from(MapConfigSource(it)) } }
    }

    @Test
    fun callbackAndLinkUrlsFollowThePublicUrls() {
        val config = IdentityConfig("https://api.ostrom.run", "https://app.ostrom.run")

        assertEquals("https://api.ostrom.run/api/v1/auth/github/callback", config.callbackUrl(IdentityProvider.GITHUB))
        assertEquals("https://app.ostrom.run/auth/email#token=t", config.magicLinkUrl("t"))
        assertEquals("https://app.ostrom.run/setup#token=osb_t", config.setupUrl("osb_t"))
    }
}
