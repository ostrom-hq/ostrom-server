// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sharing.presentation

import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.sharing.domain.EnrollSharedAccountUseCase
import run.ostrom.server.sharing.domain.HandOverSharedAccountUseCase
import run.ostrom.server.sharing.domain.ListSharedAccountsUseCase
import run.ostrom.server.sharing.domain.RemoveSharedAccountUseCase
import run.ostrom.server.sharing.domain.ShareAccountUseCase
import run.ostrom.server.sharing.domain.UpdateSharedAccountUseCase
import run.ostrom.server.sharing.domain.ViewSharedAccountUseCase
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
 * @property enroll Enrolls an account from a secret (the contract's body).
 * @property view Names the people of an account just written.
 * @property handOver Changes who renews an account's tokens.
 */
data class SharingRoutesDependencies(
    val callers: CallerResolver,
    val share: ShareAccountUseCase,
    val list: ListSharedAccountsUseCase,
    val update: UpdateSharedAccountUseCase,
    val remove: RemoveSharedAccountUseCase,
    val clock: Clock,
    val enroll: EnrollSharedAccountUseCase,
    val view: ViewSharedAccountUseCase,
    val handOver: HandOverSharedAccountUseCase,
)
