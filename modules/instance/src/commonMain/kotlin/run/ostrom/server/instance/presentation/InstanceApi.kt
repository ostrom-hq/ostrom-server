// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.instance.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `GET /api/v1/instance`: the instance and its current signing key. To move to ostrom-core with the API contract. */
@Serializable
@Resource("/api/v1/instance")
class InstanceApi {
    /**
     * `GET /api/v1/instance/keys`: every signing key the instance has used.
     *
     * @property parent The instance resource.
     */
    @Serializable
    @Resource("keys")
    class Keys(val parent: InstanceApi = InstanceApi())
}

/**
 * The instance as clients see it.
 *
 * @property id The stable id of the instance.
 * @property version The server version.
 * @property createdAt When the instance first started (ISO 8601).
 * @property signingKey The key the instance signs with now.
 */
@Serializable
data class InstanceResponse(
    val id: String,
    val version: String,
    val createdAt: String,
    val signingKey: SigningKeyResponse,
)

/**
 * A signing key as clients see it.
 *
 * @property keyId The key id (`kid`).
 * @property algorithm The algorithm, `ed25519`.
 * @property publicKeyPem The public key in PEM (SPKI).
 * @property firstSeenAt When the instance first used it (ISO 8601).
 */
@Serializable
data class SigningKeyResponse(
    val keyId: String,
    val algorithm: String,
    val publicKeyPem: String,
    val firstSeenAt: String,
)

/**
 * The list of signing keys.
 *
 * @property keys The keys, oldest first.
 */
@Serializable
data class SigningKeysResponse(val keys: List<SigningKeyResponse>)
