// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package run.ostrom.server.ee.license.infrastructure

import run.ostrom.server.billing.domain.LicenseGrant
import run.ostrom.server.ee.license.domain.License
import run.ostrom.server.ee.license.domain.LicenseException
import run.ostrom.server.ee.license.domain.LicenseInstanceMismatchException
import run.ostrom.server.ee.license.domain.LicenseVerifier
import run.ostrom.server.ee.license.domain.VerifiedLicenseProvider
import run.ostrom.server.instance.domain.GetOrCreateInstanceUseCase
import run.ostrom.server.kernel.logging.Logger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * [VerifiedLicenseProvider] on the configured key. The key is verified the first time it is needed (at start, by
 * `LicenseModule.initialize`), then cached; [current] re-reads only the clock, so a license whose grace period ends
 * while the server runs stops granting its features without a restart.
 *
 * @param licenseKey The configured key, null when none is set (`OSTROM_LICENSE_KEY` or `_FILE`). Never logged.
 * @param verifier Verifies the key.
 * @param getInstance This instance, for a license bound to one instance.
 * @param clock The clock.
 * @param logger Where the license's id, customer and expiry, or the reason it is refused, are logged (never the key).
 */
class LicenseProviderImpl(
    private val licenseKey: String?,
    private val verifier: LicenseVerifier,
    private val getInstance: GetOrCreateInstanceUseCase,
    private val clock: Clock,
    private val logger: Logger,
) : VerifiedLicenseProvider {
    private val mutex = Mutex()
    private var loaded = false
    private var license: License? = null
    private var endLogged = false

    override suspend fun load(): License? = mutex.withLock {
        if (!loaded) {
            license = licenseKey?.let { verified(it) }
            loaded = true
        }
        license
    }

    override suspend fun current(): LicenseGrant? {
        val license = load()
        return when {
            license == null -> null
            clock.now() <= license.graceEndsAt -> license.toGrant()
            else -> {
                graceOver(license)
                null
            }
        }
    }

    private suspend fun graceOver(license: License) = mutex.withLock {
        if (!endLogged) {
            endLogged = true
            logger.warn("license grace period over, enterprise features disabled", license.fields())
        }
    }

    private suspend fun verified(key: String): License {
        val license = try {
            accepted(key)
        } catch (refused: LicenseException) {
            // The server's start failure logs the exception's class only: the reason, written for the operator and
            // never quoting the key, is logged here.
            logger.error("license refused", mapOf("reason" to refused.message))
            throw refused
        }
        logger.info("license verified", license.fields())
        if (clock.now() > license.expiresAt) {
            val fields = license.fields() + ("grace_ends_at" to license.graceEndsAt.toString())
            logger.warn("license expired, in grace period", fields)
        }
        return license
    }

    private suspend fun accepted(key: String): License {
        val license = verifier.verify(key, clock.now())
        val licensed = license.instanceId
        if (licensed != null) {
            val actual = getInstance().id.toString()
            if (!licensed.equals(actual, ignoreCase = true)) throw LicenseInstanceMismatchException(licensed, actual)
        }
        return license
    }

    private fun License.fields(): Map<String, Any?> = mapOf(
        "license_id" to licenseId,
        "customer" to customer,
        "expires_at" to expiresAt.toString(),
        "max_people" to maxPeople,
        "features" to features.sorted().joinToString(","),
        "key_id" to keyId,
    )
}
