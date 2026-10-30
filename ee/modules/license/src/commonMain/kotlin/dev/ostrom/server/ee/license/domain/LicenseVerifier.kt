// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package dev.ostrom.server.ee.license.domain

import kotlin.time.Instant

/** Verifies a license key offline: no network, only the public keys compiled into the server. */
interface LicenseVerifier {
    /**
     * Verifies a license key.
     *
     * @param licenseKey The key, `ostrom-license.v1.<base64url(payload)>.<base64url(signature)>`.
     * @param now The current time, for the validity dates.
     *
     * @return The license, also during its grace period (the caller warns).
     *
     * @throws LicenseException When the key cannot be used; the subclass says why.
     */
    suspend fun verify(licenseKey: String, now: Instant): License
}
