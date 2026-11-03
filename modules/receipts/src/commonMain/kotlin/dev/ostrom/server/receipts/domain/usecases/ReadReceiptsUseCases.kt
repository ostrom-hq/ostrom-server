// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.receipts.domain.usecases

import dev.ostrom.core.receipts.ReceiptPublicKey
import dev.ostrom.core.receipts.ReceiptVerification
import dev.ostrom.core.receipts.ReceiptVerifierImpl
import dev.ostrom.server.kernel.errors.Pages
import dev.ostrom.server.receipts.domain.models.ReceiptKey
import dev.ostrom.server.receipts.domain.models.ReceiptNotFoundException
import dev.ostrom.server.receipts.domain.models.ReceiptQuery
import dev.ostrom.server.receipts.domain.models.StoredReceipt
import dev.ostrom.server.receipts.domain.models.Verification
import dev.ostrom.server.receipts.domain.repositories.ReceiptsRepository
import dev.ostrom.server.receipts.domain.services.TrustedReceiptKeys
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/** Reads a receipt of the caller's organization. */
interface GetReceiptUseCase {
    /**
     * Reads a receipt.
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

/** Lists the receipts of the caller's organization. */
interface ListReceiptsUseCase {
    /**
     * Lists receipts, newest first.
     *
     * @param orgId The caller's organization.
     * @param query The filters and page; the limit is brought within 1 to [ReceiptQuery.MAX_PAGE].
     *
     * @return The receipts.
     */
    suspend operator fun invoke(orgId: Uuid, query: ReceiptQuery): List<StoredReceipt>
}

/**
 * [ListReceiptsUseCase] on the repository.
 *
 * @param repository The receipts.
 */
class ListReceiptsUseCaseImpl(private val repository: ReceiptsRepository) : ListReceiptsUseCase {
    override suspend fun invoke(orgId: Uuid, query: ReceiptQuery): List<StoredReceipt> {
        val page = repository.list(orgId, query.copy(limit = query.limit.coerceIn(1, ReceiptQuery.MAX_PAGE)))
        // A `before` that names no receipt of the organization (forged, or another's) is a 400, not an empty page.
        return Pages.checked("before", query.before, page) { repository.get(orgId, it) != null }
    }
}

/** Verifies a receipt against the instance keys, as `ostrom verify` does offline. */
interface VerifyReceiptUseCase {
    /**
     * Verifies a stored receipt of the caller's organization.
     *
     * @param orgId The caller's organization.
     * @param id The receipt id.
     *
     * @return The outcome.
     *
     * @throws ReceiptNotFoundException When it is not one of the organization's.
     */
    suspend operator fun invoke(orgId: Uuid, id: String): Verification

    /**
     * Verifies a receipt as received, unknown fields included.
     *
     * @param receipt The receipt JSON.
     *
     * @return The outcome.
     */
    suspend fun verify(receipt: JsonObject): Verification
}

/**
 * [VerifyReceiptUseCase] with the verifier of ostrom-core.
 *
 * @param repository The receipts.
 * @param keys The instance keys.
 */
class VerifyReceiptUseCaseImpl(
    private val repository: ReceiptsRepository,
    private val keys: TrustedReceiptKeys,
) : VerifyReceiptUseCase {
    override suspend fun invoke(orgId: Uuid, id: String): Verification {
        val stored = repository.get(orgId, id) ?: throw ReceiptNotFoundException()
        return verify(stored.body)
    }

    override suspend fun verify(receipt: JsonObject): Verification {
        val trusted = keys.keys().map { ReceiptPublicKey.fromSpkiPem(it.publicKeyPem) }
        val outcome = ReceiptVerifierImpl(trusted).verify(receipt)
        return Verification(
            ok = outcome.ok,
            digest = outcome.digest,
            kid = (outcome as? ReceiptVerification.Verified)?.kid,
            reason = (outcome as? ReceiptVerification.Failed)?.let { it.reason.message },
        )
    }
}

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
