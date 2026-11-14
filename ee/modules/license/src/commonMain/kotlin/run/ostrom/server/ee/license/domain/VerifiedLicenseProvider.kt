// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package run.ostrom.server.ee.license.domain

import run.ostrom.server.billing.domain.LicenseProvider

/**
 * Billing's [LicenseProvider], backed by the license key an administrator of the instance stores in the console
 * ([LicenseSettings]). The stored key is verified at start and each time it changes, and the result is kept until it
 * changes again.
 */
interface VerifiedLicenseProvider : LicenseProvider {
    /**
     * Verifies the stored key when it was not verified yet, then returns the kept result.
     *
     * @return The license, or null when no key is stored or the stored key cannot be used (the reason is logged, and
     *   an administrator stores another in the console).
     */
    suspend fun load(): License?

    /**
     * Why a key cannot be used on this instance (its form, its signature, its dates, the instance it is bound to).
     *
     * @param licenseKey The key.
     *
     * @return The reason, never quoting the key; null when the key is usable.
     */
    suspend fun refusal(licenseKey: String): String?
}
