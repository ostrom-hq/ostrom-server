// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.sharing

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What shared accounts served, read from the usage ledger. The usage module binds it; the sharing module reads it to
 * know what a shared overflow pool lent today (TeamCode's `DailyScope.LENT`), and keeps its own default when it is
 * unbound.
 */
fun interface SharedAccountSpend {
    /**
     * What the model calls served by [accountIds] cost since [since], in one read.
     *
     * @param orgId The organization.
     * @param accountIds The shared accounts.
     * @param since The first instant counted.
     *
     * @return USD millionths by account; an account with nothing priced since then is absent.
     */
    suspend fun spentMicros(orgId: Uuid, accountIds: Collection<Uuid>, since: Instant): Map<Uuid, Long>
}
