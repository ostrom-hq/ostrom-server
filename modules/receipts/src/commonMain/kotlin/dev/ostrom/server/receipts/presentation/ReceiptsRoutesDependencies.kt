// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.receipts.presentation

import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.receipts.domain.usecases.GetReceiptUseCase
import dev.ostrom.server.receipts.domain.usecases.ListReceiptKeysUseCase
import dev.ostrom.server.receipts.domain.usecases.ListReceiptsUseCase
import dev.ostrom.server.receipts.domain.usecases.VerifyReceiptUseCase

/**
 * What the receipts routes need.
 *
 * @property orgs Resolves the caller's organization.
 * @property getReceipt Reads a receipt.
 * @property listReceipts Lists receipts.
 * @property verifyReceipt Verifies a receipt.
 * @property listKeys Lists the public keys.
 */
data class ReceiptsRoutesDependencies(
    val orgs: PrincipalOrgResolver,
    val getReceipt: GetReceiptUseCase,
    val listReceipts: ListReceiptsUseCase,
    val verifyReceipt: VerifyReceiptUseCase,
    val listKeys: ListReceiptKeysUseCase,
)
