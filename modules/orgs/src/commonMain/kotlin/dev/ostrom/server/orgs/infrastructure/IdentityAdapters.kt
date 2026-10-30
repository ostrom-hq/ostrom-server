// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.infrastructure

import dev.ostrom.server.identity.domain.AuditEntry
import dev.ostrom.server.identity.domain.AuditLog
import dev.ostrom.server.identity.domain.Mail
import dev.ostrom.server.identity.domain.MailSender
import dev.ostrom.server.identity.domain.RateLimiter
import dev.ostrom.server.identity.domain.SecretGenerator
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.User
import dev.ostrom.server.identity.domain.UsersRepository
import dev.ostrom.server.orgs.domain.INVITATION_LIFETIME
import dev.ostrom.server.orgs.domain.InvitationMailer
import dev.ostrom.server.orgs.domain.InvitationTokens
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgAudit
import dev.ostrom.server.orgs.domain.OrgEvent
import dev.ostrom.server.orgs.domain.OrgRateLimiter
import dev.ostrom.server.orgs.domain.People
import dev.ostrom.server.orgs.domain.Person
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * [People] on the identity module's users.
 *
 * @param users The identity module's users.
 */
class IdentityPeople(private val users: UsersRepository) : People {
    override suspend fun get(id: Uuid): Person? = users.get(id)?.toPerson()

    override suspend fun getMany(ids: Collection<Uuid>): List<Person> =
        users.getMany(ids).map { it.toPerson() }

    override suspend fun findByLogin(login: String): Person? = users.findByLogin(login)?.toPerson()

    override suspend fun verifiedEmails(id: Uuid): Set<String> =
        users.listEmails(id).filter { it.verified }.map { it.email.lowercase() }.toSet()

    private fun User.toPerson() = Person(id, login, displayName, avatarUrl, primaryEmail)
}

/**
 * [OrgAudit] on the identity module's signed audit log: each organization has its own chain.
 *
 * @param log The audit log.
 */
class IdentityOrgAudit(private val log: AuditLog) : OrgAudit {
    override suspend fun record(event: OrgEvent) {
        log.record(
            AuditEntry(event.orgId, event.actorId, event.action, event.targetType, event.targetId, event.details),
        )
    }
}

/**
 * [InvitationTokens] on the identity module's secret generator and hasher (256-bit random, SHA-256
 * stored).
 *
 * @param generator The generator.
 * @param hasher The hasher.
 */
class IdentityInvitationTokens(
    private val generator: SecretGenerator,
    private val hasher: SecretHasher,
) : InvitationTokens {
    override fun generate(): String = generator.secret()

    override suspend fun hash(token: String): String = hasher.hash(token)
}

/**
 * [OrgRateLimiter] on the identity module's limiter.
 *
 * @param limiter The limiter.
 */
class IdentityRateLimiter(private val limiter: RateLimiter) : OrgRateLimiter {
    override suspend fun tryAcquire(key: String, limit: Int, windowMinutes: Int): Boolean =
        limiter.tryAcquire(key, limit, windowMinutes.minutes)
}

/**
 * [InvitationMailer] on the identity module's mail sender (Resend, or a directory in tests and
 * local runs).
 *
 * @param mail The sender.
 */
class MailInvitationMailer(private val mail: MailSender) : InvitationMailer {
    override suspend fun send(to: String, org: Org, inviter: Person, link: String) {
        mail.send(invitationMail(to, org.name, inviter.displayName, link))
    }
}

/**
 * The invitation e-mail: plain HTML, no image, no tracking.
 *
 * @param to The address.
 * @param orgName The organization's name.
 * @param inviterName Who invited.
 * @param link The link that accepts it.
 * @return The mail.
 */
fun invitationMail(to: String, orgName: String, inviterName: String, link: String): Mail {
    val days = INVITATION_LIFETIME.inWholeDays
    val subject = "$inviterName invited you to $orgName on Ostrom"
    val safeLink = escapeHtml(link)
    val html =
        """
        <!doctype html>
        <html lang="en">
        <head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>${escapeHtml(subject)}</title></head>
        <body style="margin:0;padding:24px;background:#f6f6f4;font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;color:#1b1b18">
          <table role="presentation" width="100%" style="max-width:480px;margin:0 auto;background:#ffffff;border-radius:8px;padding:32px">
            <tr><td>
              <p style="margin:0 0 16px;font-size:20px;font-weight:600">Join ${escapeHtml(orgName)} on Ostrom</p>
              <p style="margin:0 0 24px;font-size:15px;line-height:22px">${escapeHtml(inviterName)} invited ${escapeHtml(to)} to the team. Sign in with this address to accept; the invitation works for $days days.</p>
              <p style="margin:0 0 24px"><a href="$safeLink" style="display:inline-block;background:#1b1b18;color:#ffffff;text-decoration:none;padding:12px 20px;border-radius:6px;font-size:15px">Accept the invitation</a></p>
              <p style="margin:0 0 8px;font-size:13px;line-height:20px;color:#5c5c56">Or paste this link in your browser:<br><span style="word-break:break-all">$safeLink</span></p>
              <p style="margin:16px 0 0;font-size:13px;line-height:20px;color:#5c5c56">If you do not know this team, ignore this e-mail: nothing happens without you.</p>
            </td></tr>
          </table>
        </body>
        </html>
    """
            .trimIndent()
    val text =
        "$inviterName invited $to to $orgName on Ostrom. Sign in with this address to accept " +
            "(the invitation works for $days days):\n\n$link\n\nIf you do not know this team, ignore this e-mail."
    return Mail(to, subject, html, text)
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
