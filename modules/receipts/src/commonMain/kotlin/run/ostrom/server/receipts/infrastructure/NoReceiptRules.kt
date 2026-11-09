// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.receipts.infrastructure

import run.ostrom.server.receipts.domain.services.ReceiptRule
import run.ostrom.server.receipts.domain.services.ReceiptRules
import kotlin.uuid.Uuid

/** The rules while no module decides any: a receipt then cites none, as the prototype does for a repository without. */
class NoReceiptRules : ReceiptRules {
    override suspend fun rulesFor(orgId: Uuid, repo: String?): List<ReceiptRule> = emptyList()
}
