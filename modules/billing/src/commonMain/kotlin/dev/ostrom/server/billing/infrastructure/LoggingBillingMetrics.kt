// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.billing.infrastructure

import dev.ostrom.server.billing.domain.BillingMetrics
import dev.ostrom.server.kernel.logging.Logger
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * [BillingMetrics] that logs each event, with its running count, until the observability module binds its
 * OpenTelemetry counters. The organization id of an unknown organization is not logged: it comes from the event.
 *
 * @param logger The logger.
 */
@OptIn(ExperimentalAtomicApi::class)
class LoggingBillingMetrics(private val logger: Logger) : BillingMetrics {
    private val unknownOrganizations = AtomicLong(0)

    /** How many events named an unknown organization since the start. */
    val ignoredUnknownOrg: Long get() = unknownOrganizations.load()

    override fun stripeEventForUnknownOrganization(eventId: String, eventType: String) {
        val count = unknownOrganizations.incrementAndFetch()
        logger.warn(
            "stripe event ignored",
            mapOf("outcome" to "ignored_unknown_org", "event_id" to eventId, "type" to eventType, "count" to count),
        )
    }
}
