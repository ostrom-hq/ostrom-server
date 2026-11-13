// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.receipts.presentation

import run.ostrom.server.receipts.domain.services.ReceiptReaders
import run.ostrom.server.receipts.domain.usecases.ListReceiptKeysUseCase
import run.ostrom.server.receipts.domain.usecases.ListReceiptsUseCase
import run.ostrom.server.receipts.domain.usecases.ReadReceiptUseCase
import run.ostrom.server.receipts.domain.usecases.VerifyReceiptUseCase

/**
 * What the receipts routes need.
 *
 * @property readers Resolves the caller: their organization, their login and whether they read every receipt.
 * @property getReceipt Reads a receipt the caller reads.
 * @property listReceipts Lists receipts.
 * @property verifyReceipt Verifies a receipt.
 * @property listKeys Lists the public keys.
 */
data class ReceiptsRoutesDependencies(
    val readers: ReceiptReaders,
    val getReceipt: ReadReceiptUseCase,
    val listReceipts: ListReceiptsUseCase,
    val verifyReceipt: VerifyReceiptUseCase,
    val listKeys: ListReceiptKeysUseCase,
)
