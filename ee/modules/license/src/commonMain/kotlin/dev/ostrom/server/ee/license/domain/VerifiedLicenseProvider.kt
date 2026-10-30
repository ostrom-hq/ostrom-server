// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package dev.ostrom.server.ee.license.domain

import dev.ostrom.server.billing.domain.LicenseProvider

/**
 * Billing's [LicenseProvider], backed by the configured license key. The key is verified once, at start, and the
 * result is kept for the life of the process.
 */
interface VerifiedLicenseProvider : LicenseProvider {
    /**
     * Verifies the configured key the first time, then returns the cached result.
     *
     * @return The license, or null when no key is configured.
     *
     * @throws LicenseException When a key is configured but cannot be used.
     */
    suspend fun load(): License?
}
