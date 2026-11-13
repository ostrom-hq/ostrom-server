// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.receipts.domain.usecases

import run.ostrom.server.receipts.domain.models.InvalidReceiptException
import run.ostrom.server.receipts.domain.models.StoredReceipt
import run.ostrom.server.receipts.domain.repositories.ReceiptsRepository
import run.ostrom.server.receipts.domain.services.ReceiptBodySigner
import run.ostrom.server.receipts.domain.services.ReceiptOwners
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Signs and stores a receipt: the one way every module (gates, sessions, compiler) signs one. */
interface SignReceiptUseCase {
    /**
     * Signs [draft] with the instance key and stores it. With an [id], signing is idempotent: a receipt already stored
     * under that id is returned as it is, never signed a second time. The signed body names [orgId] (`orgId`): the key
     * is the instance's, the same for every organization, so a receipt of one never verifies as another's.
     *
     * @param orgId The organization the receipt belongs to, written in the signed body.
     * @param draft The receipt without `orgId`, `id`, `createdAt` and `signature`: `subject` (with its `kind` and
     *   `id`), `initiator`, `rules`, `decisions`, `launchId` and `sessionId` when it has them (non-empty texts), and
     *   the optional fields of the format.
     * @param id The receipt id (`rcpt_…`), to make signing idempotent; a new one when null.
     *
     * @return The signed receipt.
     *
     * @throws InvalidReceiptException When the draft has no subject kind or id, sets a field the server sets, has a
     *   `launchId` or `sessionId` that is not a non-empty text, or the id is not a receipt id.
     */
    suspend operator fun invoke(orgId: Uuid, draft: JsonObject, id: String? = null): JsonObject
}

/**
 * [SignReceiptUseCase] with the instance signer.
 *
 * @param repository The receipts.
 * @param signer The instance key.
 * @param clock The clock.
 * @param owners The person of the session a receipt is about, stored beside it: who reads it (TeamCode #238).
 */
class SignReceiptUseCaseImpl(
    private val repository: ReceiptsRepository,
    private val signer: ReceiptBodySigner,
    private val clock: Clock,
    private val owners: ReceiptOwners,
) : SignReceiptUseCase {
    override suspend fun invoke(orgId: Uuid, draft: JsonObject, id: String?): JsonObject {
        val (kind, subjectId) = subjectOf(draft)
        ensure(SERVER_FIELDS.none { it in draft }) { "The server sets a receipt's id, createdAt and signature." }
        ensure(ORG_ID !in draft) { "The server sets a receipt's orgId." }
        ensure(BOUND_FIELDS.all { it !in draft || draft.text(it) != null }) {
            "A receipt's launchId and sessionId are non-empty texts."
        }
        ensure(id == null || RECEIPT_ID.matches(id)) { "A receipt id is rcpt_ followed by 1 to 120 characters." }
        val existing = id?.let { repository.get(orgId, it) }
        if (existing != null) return existing.body
        val receiptId = id ?: "rcpt_${Uuid.random()}"
        val now = clock.now()
        val body = JsonObject(
            draft + mapOf(
                ORG_ID to JsonPrimitive(orgId.toString()),
                "id" to JsonPrimitive(receiptId),
                "createdAt" to JsonPrimitive(now.isoMillis()),
            ),
        )

        val session = sessionOf(draft, kind, subjectId)
        val member = session?.let { owners.ownerOf(orgId, it) }
        return repository.insertIfAbsent(
            StoredReceipt(orgId, receiptId, kind, subjectId, signer.sign(body), now, session, member),
        ).body
    }

    // The session a receipt is about: the one it names, else the subject of a session receipt (as the receipts
    // migration 3 reads the receipts signed before).
    private fun sessionOf(draft: JsonObject, kind: String, subjectId: String): String? =
        draft.text(SESSION_ID) ?: subjectId.takeIf { kind == SESSION }

    private fun subjectOf(draft: JsonObject): Pair<String, String> {
        val subject = draft["subject"] as? JsonObject
        val kind = subject?.text("kind")
        val id = subject?.text("id")
        if (kind == null || id == null) throw InvalidReceiptException("A receipt names its subject: a kind and an id.")
        return kind to id
    }


    private fun JsonObject.text(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content

    // Not inline: Kover counts an inline function's own lines, never run as such.
    private fun ensure(condition: Boolean, reason: () -> String) {
        if (!condition) throw InvalidReceiptException(reason())
    }
}

/**
 * The instant as the prototype writes it (`Date.toISOString()`): UTC, always three digits of milliseconds.
 *
 * @return The ISO 8601 text, `2026-10-30T09:12:34.567Z`.
 */
fun Instant.isoMillis(): String {
    val text = Instant.fromEpochMilliseconds(toEpochMilliseconds()).toString()
    return if ('.' in text) text else text.removeSuffix("Z") + ".000Z"
}

// Top-level, not in the private companion: a companion property has a getter nothing calls, which Kover counts.
private val SERVER_FIELDS = listOf("id", "createdAt", "signature")
private const val SESSION_ID = "sessionId"
private const val SESSION = "session"
private val BOUND_FIELDS = listOf("launchId", SESSION_ID)

/** The field of a signed receipt that names its organization (contract `Receipt.orgId`). */
const val ORG_ID: String = "orgId"
private val RECEIPT_ID = Regex("rcpt_[A-Za-z0-9_.:~-]{1,120}")
