// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.Mail
import run.ostrom.server.identity.domain.MailAvailability
import run.ostrom.server.identity.domain.MailNotSentException
import run.ostrom.server.identity.domain.MailSender
import run.ostrom.server.identity.domain.trimmedOrNull
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsField
import run.ostrom.server.kernel.settings.SettingsSection
import io.ktor.client.HttpClient

/**
 * The instance's mail, set by an administrator in the console: Resend's API key (sealed) and the sender of the
 * e-mails (`Ostrom <login@mail.ostrom.run>` while unset). Sign-in links, invitations and approval e-mails go once the
 * key is set, without a restart.
 */
val MAIL_SETTINGS: SettingsSection = SettingsSection(
    "mail",
    listOf(
        SettingsField("apiKey", secret = true, required = true, legacyVariable = "OSTROM_RESEND_API_KEY"),
        SettingsField("from", legacyVariable = "OSTROM_MAIL_FROM") {
            if ('@' in it) null else "an address, such as Ostrom <login@mail.ostrom.run>."
        },
    ),
)

/**
 * [MailSender] on the instance's settings, read at each send (kept 30 seconds per replica): Resend once an
 * administrator set its key in the console, else the mail directory of a developer's machine or an end-to-end test
 * (`OSTROM_MAIL_DIR`), else nothing (a send fails, and e-mailed sign-in links are not offered).
 *
 * @param settings The instance's settings.
 * @param http The HTTP client Resend is called with.
 * @param directory The mail directory's sender, when `OSTROM_MAIL_DIR` is set.
 * @param resendUrl Resend's API URL (overridden in tests).
 */
class InstanceMailSender(
    private val settings: InstanceSettings,
    private val http: HttpClient,
    private val directory: MailSender?,
    private val resendUrl: String = "https://api.resend.com",
) : MailSender, MailAvailability {
    override suspend fun send(mail: Mail) {
        val sender = resend() ?: directory ?: throw MailNotSentException()
        sender.send(mail)
    }

    override suspend fun available(): Boolean = directory != null || resend() != null

    private suspend fun resend(): MailSender? {
        val values = settings.read(MAIL_SETTINGS.name)
        val key = trimmedOrNull(values["apiKey"], Int.MAX_VALUE)
        val from = trimmedOrNull(values["from"], Int.MAX_VALUE) ?: DEFAULT_FROM
        return if (key == null) null else ResendMailSender(http, key, from, resendUrl)
    }

    private companion object {
        const val DEFAULT_FROM = "Ostrom <login@mail.ostrom.run>"
    }
}
