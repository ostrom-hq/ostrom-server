// SPDX-License-Identifier: Apache-2.0

package run.ostrom.server.identity

import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.Mail
import run.ostrom.server.identity.domain.MailNotSentException
import run.ostrom.server.identity.fakes.SettableClock
import run.ostrom.server.identity.infrastructure.DirectoryMailSender
import run.ostrom.server.identity.infrastructure.ResendMailSender
import run.ostrom.server.identity.infrastructure.UnconfiguredMailSender
import run.ostrom.server.identity.infrastructure.magicLinkMail
import run.ostrom.server.identity.presentation.IdentityConfig
import run.ostrom.server.identity.presentation.SignInConfig
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
    fun theMailSenderIsReadFromTheEnvironment() {
        val none = SignInConfig.from(MapConfigSource(emptyMap()))
        val set = SignInConfig.from(
            MapConfigSource(
                mapOf(
                    "OSTROM_RESEND_API_KEY" to "re_x",
                    "OSTROM_MAIL_FROM" to "Me <me@x.test>",
                    "OSTROM_MAIL_DIR" to "/m",
                ),
            ),
        )

        assertEquals(SignInConfig(null, "Ostrom <login@mail.ostrom.run>", null), none)
        assertEquals(SignInConfig("re_x", "Me <me@x.test>", "/m"), set)
    }

    @Test
    fun callbackAndLinkUrlsFollowThePublicUrls() {
        val config = IdentityConfig("https://api.ostrom.run", "https://app.ostrom.run")

        assertEquals("https://api.ostrom.run/api/v1/auth/github/callback", config.callbackUrl(IdentityProvider.GITHUB))
        assertEquals("https://app.ostrom.run/auth/email#token=t", config.magicLinkUrl("t"))
        assertEquals("https://app.ostrom.run/setup#token=osb_t", config.setupUrl("osb_t"))
    }
}
