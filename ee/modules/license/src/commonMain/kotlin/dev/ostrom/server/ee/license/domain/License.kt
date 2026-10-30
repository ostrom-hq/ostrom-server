// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package dev.ostrom.server.ee.license.domain

import dev.ostrom.server.billing.domain.LicenseGrant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * How long a license keeps working after [License.expiresAt]: a renewal that arrives a few days late must not turn
 * SSO off for a whole company. The server warns at every start during this period.
 */
val LICENSE_GRACE_PERIOD: Duration = 14.days

/**
 * How far ahead of the server's clock [License.issuedAt] may be: a key installed minutes after it was signed, on a
 * server whose clock is slightly behind, is not refused.
 */
val LICENSE_CLOCK_SKEW: Duration = 5.minutes

/**
 * A verified Ostrom Enterprise license: its signature checked against a pinned key and its dates against the clock.
 *
 * @property licenseId The license's id, quoted by support and in the start log.
 * @property customer The customer's name.
 * @property issuedAt When the license was signed; it is not valid before.
 * @property expiresAt The end of the license; it keeps working for [LICENSE_GRACE_PERIOD] after.
 * @property maxPeople The licensed people, null for unlimited.
 * @property features The licensed features (`sso`, `policies`, `retention`…).
 * @property instanceId The only instance (`/v1/instance` id) the license is valid on, null for any instance.
 * @property trial Whether this is a trial: a trial may be unbound, a sold license never is (the verifier refuses it).
 * @property keyId The id of the pinned key that signed it.
 */
data class License(
    val licenseId: String,
    val customer: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val maxPeople: Int?,
    val features: Set<String>,
    val instanceId: String?,
    val trial: Boolean,
    val keyId: String,
) {
    /** The end of the grace period: from then on the license grants nothing. */
    val graceEndsAt: Instant get() = expiresAt + LICENSE_GRACE_PERIOD

    /** What billing reads from this license. */
    fun toGrant(): LicenseGrant = LicenseGrant(features, maxPeople, expiresAt)
}
