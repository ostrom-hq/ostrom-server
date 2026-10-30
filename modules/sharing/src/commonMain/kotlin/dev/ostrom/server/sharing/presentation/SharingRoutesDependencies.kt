// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing.presentation

import dev.ostrom.server.secrets.domain.CallerResolver
import dev.ostrom.server.sharing.domain.ListSharedAccountsUseCase
import dev.ostrom.server.sharing.domain.RemoveSharedAccountUseCase
import dev.ostrom.server.sharing.domain.ShareAccountUseCase
import dev.ostrom.server.sharing.domain.UpdateSharedAccountUseCase
import kotlin.time.Clock

/**
 * What the sharing routes need.
 *
 * @property callers Resolves the member a credential acts for.
 * @property share Shares an account.
 * @property list Lists the org's accounts.
 * @property update Changes one.
 * @property remove Removes one.
 * @property clock The time of the capacity answer.
 */
data class SharingRoutesDependencies(
    val callers: CallerResolver,
    val share: ShareAccountUseCase,
    val list: ListSharedAccountsUseCase,
    val update: UpdateSharedAccountUseCase,
    val remove: RemoveSharedAccountUseCase,
    val clock: Clock,
)
