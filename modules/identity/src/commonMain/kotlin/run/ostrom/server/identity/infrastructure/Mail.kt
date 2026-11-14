// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.MAGIC_LINK_LIFETIME
import run.ostrom.server.identity.domain.Mail
import run.ostrom.server.identity.domain.MailNotSentException
import run.ostrom.server.identity.domain.MailSender
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * [MailSender] on Resend's API (`POST /emails`).
 *
 * @param http The HTTP client.
 * @param apiKey The Resend API key (the `mail` settings of the console).
 * @param from The sender, such as `Ostrom <login@mail.ostrom.run>`.
 * @param apiUrl Resend's API URL (overridden in tests).
 */
class ResendMailSender(
    private val http: HttpClient,
    private val apiKey: String,
    private val from: String,
    private val apiUrl: String = "https://api.resend.com",
) : MailSender {
    override suspend fun send(mail: Mail) {
        val email = ResendEmail(from, listOf(mail.to), mail.subject, mail.html, mail.text)
        val sent = try {
            http.post("$apiUrl/emails") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                contentType(ContentType.Application.Json)
                setBody(ProviderJson.encodeToString(ResendEmail.serializer(), email))
            }.status.isSuccess()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Never the provider's message in a log or an answer: the caller only learns that the mail did not go.
            false
        }
        if (!sent) throw MailNotSentException()
    }

    @Serializable
    private data class ResendEmail(
        val from: String,
        val to: List<String>,
        val subject: String,
        val html: String,
        val text: String,
    )
}

/**
 * [MailSender] that writes each mail as an `.eml`-like text file in a directory (`OSTROM_MAIL_DIR`): for a developer's
 * machine and the end-to-end tests, which read the sign-in link from it. Never used once Resend is set in the console.
 *
 * @param directory The directory, created if missing.
 * @param clock Names the files in order.
 */
class DirectoryMailSender(private val directory: String, private val clock: Clock) : MailSender {
    override suspend fun send(mail: Mail) {
        try {
            val dir = Path(directory)
            SystemFileSystem.createDirectories(dir)
            val recipient = mail.to.lowercase().replace(Regex("[^a-z0-9@._-]"), "_")
            val name = "${clock.now().toEpochMilliseconds()}-$recipient.txt"
            SystemFileSystem.sink(Path(dir, name)).buffered().use { sink ->
                sink.writeString("To: ${mail.to}\nSubject: ${mail.subject}\n\n${mail.text}\n")
            }
        } catch (_: IOException) {
            throw MailNotSentException()
        }
    }
}

/**
 * The sign-in e-mail: plain HTML that renders the same in every client (no image, no web font, no tracking), and its
 * text version.
 *
 * @param to The recipient.
 * @param link The sign-in link.
 *
 * @return The mail.
 */
fun magicLinkMail(to: String, link: String): Mail {
    val minutes = MAGIC_LINK_LIFETIME.inWholeMinutes
    val safeLink = escapeHtml(link)
    val html = """
        <!doctype html>
        <html lang="en">
        <head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Sign in to Ostrom</title></head>
        <body style="margin:0;padding:24px;background:#f6f6f4;font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;color:#1b1b18">
          <table role="presentation" width="100%" style="max-width:480px;margin:0 auto;background:#ffffff;border-radius:8px;padding:32px">
            <tr><td>
              <p style="margin:0 0 16px;font-size:20px;font-weight:600">Sign in to Ostrom</p>
              <p style="margin:0 0 24px;font-size:15px;line-height:22px">Use the button below to sign in as ${escapeHtml(to)}. It works once, for $minutes minutes.</p>
              <p style="margin:0 0 24px"><a href="$safeLink" style="display:inline-block;background:#1b1b18;color:#ffffff;text-decoration:none;padding:12px 20px;border-radius:6px;font-size:15px">Sign in</a></p>
              <p style="margin:0 0 8px;font-size:13px;line-height:20px;color:#5c5c56">Or paste this link in your browser:<br><span style="word-break:break-all">$safeLink</span></p>
              <p style="margin:16px 0 0;font-size:13px;line-height:20px;color:#5c5c56">If you did not ask to sign in, ignore this e-mail: nothing happens without the link.</p>
            </td></tr>
          </table>
        </body>
        </html>
    """.trimIndent()
    val text = "Sign in to Ostrom as $to with this link (it works once, for $minutes minutes):\n\n$link\n\n" +
        "If you did not ask to sign in, ignore this e-mail: nothing happens without the link."
    return Mail(to, "Sign in to Ostrom", html, text)
}

private fun escapeHtml(value: String): String = buildString {
    value.forEach {
        when (it) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(it)
        }
    }
}
