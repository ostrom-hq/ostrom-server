// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import run.ostrom.server.identity.domain.Mail
import run.ostrom.server.identity.domain.MailAvailability
import run.ostrom.server.identity.domain.MailSender
import run.ostrom.server.kernel.events.GateSummary

/**
 * Sends one approval delivery: a push to every phone the approver registered in the gate's organization, unless they
 * turned requests off since, or the e-mail to their proven address. A phone of another organization is never read,
 * a phone whose credential was revoked or expired is skipped. The approver is read again at each attempt: a switch
 * turned off, an address no longer proven or a member who left stops what was queued.
 *
 * @param approvers Finds the approver among the members.
 * @param devices The phones.
 * @param credentials Whether a phone's credential still works.
 * @param notices The texts.
 * @param push The push service.
 * @param mail The mail provider.
 * @param mailing Whether the instance sends e-mails now.
 */
@Suppress("LongParameterList") // The sends' collaborators, each a port.
class ApprovalSender(
    private val approvers: Approvers,
    private val devices: PushDevicesRepository,
    private val credentials: LiveCredentials,
    private val notices: ApprovalNotices,
    private val push: PushSender,
    private val mail: MailSender,
    private val mailing: MailAvailability,
) {
    /**
     * @param delivery The delivery.
     * @param gate Its gate, as it is now.
     *
     * @return What the attempt produced; never throws but for cancellation and a failed read.
     */
    suspend fun send(delivery: ApprovalDelivery, gate: GateSummary): ChannelResult {
        val approver = approvers.reach(delivery.orgId, setOf(delivery.approver))[delivery.approver]
            ?: return ChannelResult.failed(NOT_A_MEMBER)
        return when (delivery.channel) {
            PUSH_CHANNEL -> push(delivery, approver)
            ApprovalRules.EMAIL_CHANNEL -> email(delivery, approver, gate)
            else -> ChannelResult.failed(CHANNEL_UNAVAILABLE)
        }
    }

    // One push per phone, apart: a phone the provider refuses is tried again alone, never the approver's others.
    private suspend fun push(delivery: ApprovalDelivery, approver: Approver): ChannelResult {
        val phones = if (approver.pushes) phones(delivery, approver) else null
        return when {
            phones == null -> ChannelResult.failed(REQUESTS_OFF)
            phones.isEmpty() -> ChannelResult.failed(NO_DEVICE)
            else -> {
                val results = coroutineScope {
                    phones.map { async { pushTo(notices.push(delivery.approvalId, it)) } }.awaitAll()
                }
                // Retried while one phone may still get it (the pushes about one request collapse on the phones that
                // already have it), delivered when one did, failed otherwise.
                results.firstOrNull { it.outcome == ChannelResult.Outcome.RETRY }
                    ?: results.firstOrNull { it.outcome == ChannelResult.Outcome.DELIVERED }
                    ?: results.first()
            }
        }
    }

    // The approver's live phones of the gate's organization. An older app's Expo phone has no key: never notified.
    private suspend fun phones(delivery: ApprovalDelivery, approver: Approver): List<PushDevice> =
        devices.ofMember(delivery.orgId, approver.memberId).filter { device ->
            val credential = device.credential
            device.provider == FCM && credential != null && credentials.live(approver.userId, credential)
        }

    private suspend fun pushTo(message: PushMessage): ChannelResult = try {
        push.send(listOf(message))
        ChannelResult.DELIVERED
    } catch (error: CancellationException) {
        throw error
    } catch (error: PushNotSentException) {
        // Our own words (the provider's status or code), never a token.
        if (error.forGood) ChannelResult.failed(error.reason) else ChannelResult.retry(error.reason)
    } catch (_: Exception) {
        ChannelResult.retry(PUSH)
    }

    private suspend fun email(delivery: ApprovalDelivery, approver: Approver, gate: GateSummary): ChannelResult {
        val to = approver.email
        return when {
            to == null -> ChannelResult.failed(NO_EMAIL)
            // Queued while the instance sent mail: a setting off for now is tried again, then given up.
            !mailing.available() -> ChannelResult.retry(MAIL_OFF)
            else -> mailTo(notices.email(gate, delivery.approvalId, to))
        }
    }

    private suspend fun mailTo(message: Mail): ChannelResult = try {
        mail.send(message)
        ChannelResult.DELIVERED
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // The provider's message may quote the address: the code only.
        ChannelResult.retry(MAIL)
    }

    private companion object {
        const val NOT_A_MEMBER = "not_a_member"
        const val CHANNEL_UNAVAILABLE = "channel_unavailable"
        const val REQUESTS_OFF = "requests_off"
        const val NO_DEVICE = "no_device"
        const val NO_EMAIL = "no_email"
        const val MAIL_OFF = "mail_off"
        const val PUSH = "push"
        const val MAIL = "mail"
    }
}
