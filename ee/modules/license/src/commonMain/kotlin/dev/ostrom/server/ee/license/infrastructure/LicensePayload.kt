// SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise
package dev.ostrom.server.ee.license.infrastructure

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The signed JSON of a v1 license key, as `ee/tools/license/sign.py` writes it. Dates are ISO-8601 UTC strings,
 * parsed after the signature is checked.
 *
 * @property licenseId The license's id.
 * @property customer The customer's name.
 * @property issuedAt When the license was signed.
 * @property expiresAt The end of the license.
 * @property maxPeople The licensed people, null for unlimited.
 * @property features The licensed features.
 * @property keyId The id of the key that signed the payload (16 hex of SHA-256 of its SPKI DER).
 * @property instanceId The only instance the license is valid on, absent for any instance.
 */
@Serializable
internal data class LicensePayload(
    val licenseId: String,
    val customer: String,
    val issuedAt: String,
    val expiresAt: String,
    val maxPeople: Int? = null,
    val features: List<String> = emptyList(),
    val keyId: String,
    val instanceId: String? = null,
)

/**
 * The JSON of license payloads. Deliberately not the API's `OstromJson`, which ignores unknown keys: a field this
 * build does not know could be a restriction a newer issuer added, so a v1 payload with one is refused rather than
 * read as if the restriction were absent.
 */
internal val LicenseJson: Json = Json { ignoreUnknownKeys = false }
