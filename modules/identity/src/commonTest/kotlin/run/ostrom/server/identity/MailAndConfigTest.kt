// SPDX-License-Identifier: Apache-2.0

package run.ostrom.server.identity

import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.Mail
import run.ostrom.server.identity.domain.MailNotSentException
import run.ostrom.server.identity.fakes.RecordingMailSender
import run.ostrom.server.identity.fakes.SettableClock
import run.ostrom.server.identity.infrastructure.DirectoryMailSender
import run.ostrom.server.identity.infrastructure.ResendMailSender
import run.ostrom.server.identity.infrastructure.InstanceMailSender
import run.ostrom.server.identity.infrastructure.MAIL_SETTINGS
import run.ostrom.server.identity.infrastructure.magicLinkMail
import run.ostrom.server.identity.presentation.IdentityConfig
import run.ostrom.server.identity.presentation.SignInConfig
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.testing.TestInstanceSettings
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
    fun onlyTheMailDirectoryIsReadFromTheEnvironment() {
        val ignored = mapOf("OSTROM_RESEND_API_KEY" to "re_x", "OSTROM_MAIL_FROM" to "Me <me@x.test>")

        assertEquals(SignInConfig(null), SignInConfig.from(MapConfigSource(ignored)))
        assertEquals(SignInConfig("/m"), SignInConfig.from(MapConfigSource(ignored + ("OSTROM_MAIL_DIR" to " /m "))))
    }

    @Test
    fun theMailGoesThroughResendOnceItsKeyIsSetInTheConsole() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val http = HttpClient(MockEngine { requests += it; respond("""{"id":"e1"}""") })
        val settings = TestInstanceSettings()
        val directory = RecordingMailSender()
        val withDirectory = InstanceMailSender(settings, http, directory, "https://resend.test")
        val alone = InstanceMailSender(settings, http, null, "https://resend.test")

        assertTrue(withDirectory.available())
        assertFalse(alone.available())
        withDirectory.send(mail)
        assertEquals(listOf(mail), directory.sent)
        assertFailsWith<MailNotSentException> { alone.send(mail) }

        settings.save("mail", mapOf("apiKey" to " re_key "))
        assertTrue(alone.available())
        withDirectory.send(mail)
        assertEquals(listOf(mail), directory.sent, "Resend, not the directory")
        assertTrue("Ostrom <login@mail.ostrom.run>" in requests.single().bodyText())
        assertEquals("Bearer re_key", requests.single().headers[HttpHeaders.Authorization])
        settings.save("mail", mapOf("apiKey" to "re_key", "from" to "Acme <ostrom@acme.test>"))
        alone.send(mail)
        assertTrue("Acme <ostrom@acme.test>" in requests.last().bodyText())
    }

    @Test
    fun theMailSectionNamesItsFormerVariablesAndSealsTheKey() {
        val variables = MAIL_SETTINGS.fields.map { it.legacyVariable }
        assertEquals(listOf("OSTROM_RESEND_API_KEY", "OSTROM_MAIL_FROM"), variables)
        assertTrue(MAIL_SETTINGS.field("apiKey")!!.secret)
        val from = MAIL_SETTINGS.field("from")!!
        assertNull(from.check("Ostrom <login@mail.ostrom.run>"))
        assertEquals("an address, such as Ostrom <login@mail.ostrom.run>.", from.check("Ostrom"))
    }

    private fun HttpRequestData.bodyText() = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    @Test
    fun callbackAndLinkUrlsFollowThePublicUrls() {
        val config = IdentityConfig("https://api.ostrom.run", "https://app.ostrom.run")

        assertEquals("https://api.ostrom.run/api/v1/auth/github/callback", config.callbackUrl(IdentityProvider.GITHUB))
        assertEquals("https://app.ostrom.run/auth/email#token=t", config.magicLinkUrl("t"))
        assertEquals("https://app.ostrom.run/setup#token=osb_t", config.setupUrl("osb_t"))
    }
}
