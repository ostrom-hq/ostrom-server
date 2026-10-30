// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package dev.ostrom.server.ee.license.domain

import kotlin.time.Instant

/**
 * A license key that cannot be used. The server refuses to start with one: an operator who set a key wants to know it
 * does not work, not to run silently without its features. Messages never contain the key itself.
 */
sealed class LicenseException(message: String) : Exception(message)

/** The key is not an `ostrom-license.v1.` key, or its payload is not a valid v1 payload. */
class MalformedLicenseException(reason: String) : LicenseException("The license key is malformed: $reason")

/** The key was signed by a key this server does not trust (another vendor, or a key retired from the build). */
class UnknownLicenseKeyException(keyId: String) :
    LicenseException("The license key was signed by an unknown key ($keyId); this build does not trust it.")

/** The signature does not match the payload: the key was altered or was not signed by Ostrom. */
class InvalidLicenseSignatureException :
    LicenseException("The license key's signature is invalid: the key was altered or not issued by Ostrom.")

/** The license and its grace period are over. */
class ExpiredLicenseException(expiresAt: Instant, graceEndsAt: Instant) :
    LicenseException("The license expired on $expiresAt and its grace period ended on $graceEndsAt; renew it.")

/** The license was issued after the server's current time: the server's clock is wrong, or the key is. */
class NotYetValidLicenseException(issuedAt: Instant) :
    LicenseException("The license is not valid before $issuedAt; check the server's clock.")

/** The license is bound to another instance; the message says how to get it re-issued. */
class LicenseInstanceMismatchException(licensed: String, actual: String) :
    LicenseException(
        "The license is for instance $licensed, not this instance ($actual). " +
            "To have it re-issued for this instance, send this instance id (GET /api/v1/instance) to sales@ostrom.run.",
    )
