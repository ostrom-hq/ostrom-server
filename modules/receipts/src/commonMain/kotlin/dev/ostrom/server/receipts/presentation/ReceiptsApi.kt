// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.receipts.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// The routes and bodies of `ostrom-core/api/openapi.yaml` (tag `receipts`). A receipt is served as the JSON that was
// signed, unknown fields included: decoding it into a model would drop the fields the model does not know, and the
// receipt would no longer verify.

/**
 * The receipts of the caller's organization (contract `listReceipts`).
 *
 * @property subjectKind Only this kind.
 * @property subjectId Only this subject.
 * @property before The cursor of the next page (`next` of the previous one).
 * @property limit The page size.
 */
@Serializable
@Resource("/api/v1/receipts")
class ReceiptsApi(
    val subjectKind: String? = null,
    val subjectId: String? = null,
    val before: String? = null,
    val limit: Int? = null,
) {
    /**
     * The instance's public keys (contract `listReceiptKeys`), served without a token.
     *
     * @property parent The receipts resource.
     */
    @Serializable
    @Resource("keys")
    class Keys(val parent: ReceiptsApi = ReceiptsApi())

    /**
     * One receipt (contract `getReceipt`).
     *
     * @property parent The receipts resource.
     * @property id The receipt id.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: ReceiptsApi = ReceiptsApi(), val id: String) {
        /**
         * Its verification with the instance keys (contract `verifyReceipt`).
         *
         * @property parent The receipt.
         */
        @Serializable
        @Resource("verify")
        class Verify(val parent: Id)
    }
}

/**
 * A page of receipts.
 *
 * @property items The receipts, newest first.
 * @property next The cursor of the next page, when the page is full.
 */
@Serializable
data class ReceiptsPage(val items: List<JsonObject>, val next: String? = null)

/**
 * A public key (contract `PublicKey`).
 *
 * @property kid The key id.
 * @property alg `ed25519`.
 * @property publicKeyPem The SPKI PEM.
 * @property rotation How the key it replaced handed it over; absent for a key handed over by nobody.
 */
@Serializable
data class PublicKeyResponse(
    val kid: String,
    val alg: String,
    val publicKeyPem: String,
    val rotation: KeyRotationResponse? = null,
)

/**
 * How a receipt key was handed over (contract `KeyRotation`, docs/receipt-key-rotation.md).
 *
 * @property kid The id of the key it replaces.
 * @property signature That key's Ed25519 signature, in base64, over `ostrom-receipt-key-rotation/v1\n` followed by
 * the hex of the new key's 32 raw bytes.
 */
@Serializable
data class KeyRotationResponse(val kid: String, val signature: String)

/**
 * The public keys (contract `listReceiptKeys`).
 *
 * @property keys The keys, oldest first.
 */
@Serializable
data class ReceiptKeysResponse(val keys: List<PublicKeyResponse>)

/**
 * A verification (contract `Verification`).
 *
 * @property ok True when the receipt is intact and signed by a key of the instance.
 * @property digest The SHA-256 of the canonical body, recomputed.
 * @property kid The key that signed it.
 * @property reason Why it does not verify.
 * @property orgBound True when the signed body names the caller's organization; false for a receipt signed before
 * receipts named theirs, never shown as bound.
 */
@Serializable
data class VerificationResponse(
    val ok: Boolean,
    val digest: String? = null,
    val kid: String? = null,
    val reason: String? = null,
    val orgBound: Boolean = false,
)
