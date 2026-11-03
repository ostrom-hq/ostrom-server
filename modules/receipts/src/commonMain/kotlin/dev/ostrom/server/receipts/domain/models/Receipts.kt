// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.receipts.domain.models

import dev.ostrom.server.kernel.errors.ErrorKind
import dev.ostrom.server.kernel.errors.OstromException
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A signed receipt as stored: its JSON exactly as signed, unknown fields included, since the signature covers every
 * field (format v1 of the prototype, `mvp/server/src/receipts.ts`).
 *
 * @property orgId The organization it belongs to.
 * @property id The receipt id (`rcpt_…`).
 * @property subjectKind The kind of what it is about (`gate`, `session`, `workflow`…).
 * @property subjectId The id of what it is about.
 * @property body The signed receipt, `signature` included.
 * @property createdAt When it was signed.
 */
data class StoredReceipt(
    val orgId: Uuid,
    val id: String,
    val subjectKind: String,
    val subjectId: String,
    val body: JsonObject,
    val createdAt: Instant,
)

/**
 * Which receipts to list, newest first.
 *
 * @property subjectKind Only this kind, when set.
 * @property subjectId Only this subject, when set.
 * @property before Only the receipts signed before this one (the cursor of the previous page), when set.
 * @property limit At most this many.
 */
data class ReceiptQuery(
    val subjectKind: String? = null,
    val subjectId: String? = null,
    val before: String? = null,
    val limit: Int = DEFAULT_PAGE,
) {
    /** The page bounds. */
    companion object {
        /** The default page size. */
        const val DEFAULT_PAGE: Int = 50

        /** The largest page. */
        const val MAX_PAGE: Int = 200
    }
}

/**
 * A public key receipts are verified against: a key the instance signs or once signed with.
 *
 * @property kid The key id, the first 16 hex characters of the SHA-256 of the SPKI DER.
 * @property algorithm `ed25519`.
 * @property publicKeyPem The SPKI PEM.
 * @property rotation How the key it replaced handed it over, or null.
 */
data class ReceiptKey(
    val kid: String,
    val algorithm: String,
    val publicKeyPem: String,
    val rotation: ReceiptKeyRotation? = null,
)

/**
 * A receipt key handed over by the key it replaces (docs/receipt-key-rotation.md).
 *
 * @property kid The previous key's id.
 * @property signature The previous key's Ed25519 signature (base64) over `ostrom-receipt-key-rotation/v1\n` and the
 * hex of the new key's 32 raw bytes.
 */
data class ReceiptKeyRotation(val kid: String, val signature: String)

/**
 * The outcome of a verification, what `ostrom verify` would say offline with the same keys.
 *
 * @property ok True when the receipt is intact and signed by a trusted key.
 * @property digest The SHA-256 of the canonical body, recomputed.
 * @property kid The key that signed it, when verified.
 * @property reason Why it does not verify, when it does not.
 * @property orgBound True when its signed body names the organization that verifies it; false for a receipt signed
 * before receipts named their organization (valid, never shown as bound) and for one that does not verify.
 */
data class Verification(
    val ok: Boolean,
    val digest: String?,
    val kid: String?,
    val reason: String?,
    val orgBound: Boolean,
)

/** The receipt does not exist, or belongs to another organization (the same answer, so nothing leaks). */
class ReceiptNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Receipt not found.")

/**
 * A receipt to sign is malformed: the caller is another module, so this is a defect, not a user error.
 *
 * @param reason What is wrong.
 */
class InvalidReceiptException(reason: String) : OstromException(ErrorKind.INVALID_REQUEST, reason)
