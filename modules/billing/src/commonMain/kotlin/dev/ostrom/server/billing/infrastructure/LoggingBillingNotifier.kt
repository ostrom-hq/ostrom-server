// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing.infrastructure

import dev.ostrom.server.billing.domain.BillingNotifier
import dev.ostrom.server.billing.domain.PaymentIssue
import dev.ostrom.server.kernel.logging.Logger
import kotlin.uuid.Uuid

/**
 * [BillingNotifier] that logs, until the notifications module binds the e-mail and console ones. The console shows
 * the alerts already: they are stored and returned by `GET /v1/orgs/{orgId}/billing`. Stripe e-mails the customer
 * about failed payments itself.
 *
 * @param logger The logger.
 */
class LoggingBillingNotifier(private val logger: Logger) : BillingNotifier {
    override suspend fun agentHoursReached(orgId: Uuid, percent: Int, usedHours: Long, limitHours: Int) {
        logger.info(
            "agent hours alert",
            mapOf("org_id" to orgId, "percent" to percent, "used_hours" to usedHours, "limit_hours" to limitHours),
        )
    }

    override suspend fun paymentIssue(orgId: Uuid, issue: PaymentIssue) {
        logger.warn("payment issue", mapOf("org_id" to orgId, "kind" to issue.kind.key))
    }
}
