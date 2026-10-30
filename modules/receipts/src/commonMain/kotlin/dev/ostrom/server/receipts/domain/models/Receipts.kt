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
 */
data class ReceiptKey(val kid: String, val algorithm: String, val publicKeyPem: String)

/**
 * The outcome of a verification, what `ostrom verify` would say offline with the same keys.
 *
 * @property ok True when the receipt is intact and signed by a trusted key.
 * @property digest The SHA-256 of the canonical body, recomputed.
 * @property kid The key that signed it, when verified.
 * @property reason Why it does not verify, when it does not.
 */
data class Verification(val ok: Boolean, val digest: String?, val kid: String?, val reason: String?)

/** The receipt does not exist, or belongs to another organization (the same answer, so nothing leaks). */
class ReceiptNotFoundException : OstromException(ErrorKind.NOT_FOUND, "Receipt not found.")

/**
 * A receipt to sign is malformed: the caller is another module, so this is a defect, not a user error.
 *
 * @param reason What is wrong.
 */
class InvalidReceiptException(reason: String) : OstromException(ErrorKind.INVALID_REQUEST, reason)
