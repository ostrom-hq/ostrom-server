// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing.infrastructure

import run.ostrom.server.billing.domain.BillingNotifier
import run.ostrom.server.billing.domain.PaymentIssue
import run.ostrom.server.kernel.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

/**
 * Wraps the notifier the use cases call after their write is committed: a notification that fails (an e-mail
 * provider down) is logged, never thrown. Thrown, it would make the caller retry, and the retry count the usage twice.
 *
 * @param delegate The notifications module's notifier, or the logging one.
 * @param logger Where failures are logged.
 */
class GuardedBillingNotifier(private val delegate: BillingNotifier, private val logger: Logger) : BillingNotifier {
    override suspend fun agentHoursReached(orgId: Uuid, percent: Int, usedHours: Long, limitHours: Int) =
        guard(orgId, "agent_hours") { delegate.agentHoursReached(orgId, percent, usedHours, limitHours) }

    override suspend fun paymentIssue(orgId: Uuid, issue: PaymentIssue) =
        guard(orgId, "payment_issue") { delegate.paymentIssue(orgId, issue) }

    private suspend fun guard(orgId: Uuid, kind: String, send: suspend () -> Unit) {
        try {
            send()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn(
                "billing notification failed",
                mapOf("org_id" to orgId, "kind" to kind, "error" to error::class.simpleName),
            )
        }
    }
}
