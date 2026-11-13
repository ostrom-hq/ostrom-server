// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.receipts.domain.usecases

import run.ostrom.core.receipts.ReceiptPublicKey
import run.ostrom.core.receipts.ReceiptVerification
import run.ostrom.core.receipts.ReceiptVerifierImpl
import run.ostrom.server.kernel.errors.Pages
import run.ostrom.server.receipts.domain.models.ReceiptKey
import run.ostrom.server.receipts.domain.models.ReceiptNotFoundException
import run.ostrom.server.receipts.domain.models.ReceiptQuery
import run.ostrom.server.receipts.domain.models.ReceiptReader
import run.ostrom.server.receipts.domain.models.StoredReceipt
import run.ostrom.server.receipts.domain.models.Verification
import run.ostrom.server.receipts.domain.repositories.ReceiptsRepository
import run.ostrom.server.receipts.domain.services.TrustedReceiptKeys
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

/** Reads a receipt of the caller's organization. */
interface GetReceiptUseCase {
    /**
     * Reads a receipt, for another module that checked who reads it (a session's, a gate's).
     *
     * @param orgId The caller's organization.
     * @param id The receipt id.
     *
     * @return The signed receipt.
     *
     * @throws ReceiptNotFoundException When it is not one of the organization's.
     */
    suspend operator fun invoke(orgId: Uuid, id: String): JsonObject
}

/**
 * [GetReceiptUseCase] on the repository.
 *
 * @param repository The receipts.
 */
class GetReceiptUseCaseImpl(private val repository: ReceiptsRepository) : GetReceiptUseCase {
    override suspend fun invoke(orgId: Uuid, id: String): JsonObject {
        val stored = repository.get(orgId, id) ?: throw ReceiptNotFoundException()
        return stored.body
    }
}

/** Reads a receipt for the receipts routes: one the caller may read ([ReceiptAccess], TeamCode #238). */
interface ReadReceiptUseCase {
    /**
     * Reads a receipt.
     *
     * @param reader The caller.
     * @param id The receipt id.
     *
     * @return The signed receipt.
     *
     * @throws ReceiptNotFoundException When it is not one of the organization's, or not one the caller reads.
     */
    suspend operator fun invoke(reader: ReceiptReader, id: String): JsonObject
}

/**
 * [ReadReceiptUseCase] on who reads a receipt.
 *
 * @param access Who reads a receipt.
 */
class ReadReceiptUseCaseImpl(private val access: ReceiptAccess) : ReadReceiptUseCase {
    override suspend fun invoke(reader: ReceiptReader, id: String): JsonObject = access.read(reader, id).body
}

/** Lists the receipts of the caller's organization the caller reads. */
interface ListReceiptsUseCase {
    /**
     * Lists receipts, newest first: every one for an owner or an administrator, those the caller reads otherwise
     * ([ReceiptAccess]).
     *
     * @param reader The caller.
     * @param query The filters and page; the limit is brought within 1 to [ReceiptQuery.MAX_PAGE].
     *
     * @return The receipts.
     *
     * @throws run.ostrom.server.kernel.errors.InvalidPageException When `before` names no receipt the caller reads.
     */
    suspend operator fun invoke(reader: ReceiptReader, query: ReceiptQuery): List<StoredReceipt>
}

/**
 * [ListReceiptsUseCase] on the repository.
 *
 * @param repository The receipts.
 * @param access Who reads a receipt.
 */
class ListReceiptsUseCaseImpl(
    private val repository: ReceiptsRepository,
    private val access: ReceiptAccess,
) : ListReceiptsUseCase {
    override suspend fun invoke(reader: ReceiptReader, query: ReceiptQuery): List<StoredReceipt> {
        val audience = access.audience(reader)
        // A `before` that names no receipt the caller reads (forged, another organization's, or a session's they do
        // not read) is a 400, not an empty page. A partial reader's is checked before any page, whose start would
        // otherwise order their receipts against one they do not read; the others' only on an empty page.
        val before = query.before
        if (audience != null && before != null && !readable(reader, before)) Pages.unknownCursor("before")
        val limit = query.limit.coerceIn(1, ReceiptQuery.MAX_PAGE)
        val page = repository.list(reader.orgId, query.copy(limit = limit, audience = audience))
        return Pages.checked("before", before, page) { readable(reader, it) }
    }

    private suspend fun readable(reader: ReceiptReader, id: String): Boolean =
        repository.get(reader.orgId, id)?.let { access.canRead(reader, it) } == true
}

/** Verifies a receipt against the instance keys, as `ostrom verify` does offline. */
interface VerifyReceiptUseCase {
    /**
     * Verifies a stored receipt the caller may read ([ReceiptAccess]).
     *
     * @param reader The caller.
     * @param id The receipt id.
     *
     * @return The outcome.
     *
     * @throws ReceiptNotFoundException When it is not one of the organization's, or not one the caller reads.
     */
    suspend operator fun invoke(reader: ReceiptReader, id: String): Verification

    /**
     * Verifies a receipt as received, unknown fields included, for the caller's organization: a receipt whose signed
     * body names another organization does not verify; one signed before receipts named their organization verifies
     * by its signature, never bound to the caller's ([Verification.orgBound] false).
     *
     * @param orgId The caller's organization.
     * @param receipt The receipt JSON.
     *
     * @return The outcome.
     */
    suspend fun verify(orgId: Uuid, receipt: JsonObject): Verification
}

/**
 * [VerifyReceiptUseCase] with the verifier of ostrom-core.
 *
 * @param access Who reads a receipt.
 * @param keys The instance keys.
 */
class VerifyReceiptUseCaseImpl(
    private val access: ReceiptAccess,
    private val keys: TrustedReceiptKeys,
) : VerifyReceiptUseCase {
    override suspend fun invoke(reader: ReceiptReader, id: String): Verification =
        verify(reader.orgId, access.read(reader, id).body)

    override suspend fun verify(orgId: Uuid, receipt: JsonObject): Verification {
        val trusted = keys.keys().map { ReceiptPublicKey.fromSpkiPem(it.publicKeyPem) }
        val outcome = ReceiptVerifierImpl(trusted).verify(receipt)
        val named = receipt[ORG_ID]
        val bound = named == JsonPrimitive(orgId.toString())
        // Named, but not this organization (or not as a text): the signature holds, the receipt is someone else's.
        val foreign = outcome.ok && named != null && !bound
        val failure = (outcome as? ReceiptVerification.Failed)?.let { it.reason.message }
        return Verification(
            ok = outcome.ok && !foreign,
            digest = outcome.digest,
            kid = (outcome as? ReceiptVerification.Verified)?.kid,
            reason = if (foreign) ANOTHER_ORGANIZATION else failure,
            orgBound = outcome.ok && bound,
        )
    }
}

private const val ANOTHER_ORGANIZATION = "This receipt was signed for another organization."

/** Lists the public keys receipts are verified against; public, so anyone can verify offline. */
interface ListReceiptKeysUseCase {
    /**
     * Lists the keys.
     *
     * @return The keys, oldest first.
     */
    suspend operator fun invoke(): List<ReceiptKey>
}

/**
 * [ListReceiptKeysUseCase] on the instance keys.
 *
 * @param keys The instance keys.
 */
class ListReceiptKeysUseCaseImpl(private val keys: TrustedReceiptKeys) : ListReceiptKeysUseCase {
    override suspend fun invoke(): List<ReceiptKey> = keys.keys()
}
