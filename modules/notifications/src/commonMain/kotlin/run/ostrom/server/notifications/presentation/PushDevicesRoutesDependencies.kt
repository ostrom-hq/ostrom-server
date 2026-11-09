// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.notifications.presentation

import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.notifications.domain.DeletePushDeviceUseCase
import run.ostrom.server.notifications.domain.RegisterPushDeviceUseCase

/**
 * What the phones routes need.
 *
 * @property orgs The member the caller acts for.
 * @property registerPhone Registers a phone.
 * @property removePhone Removes one.
 */
data class PushDevicesRoutesDependencies(
    val orgs: PrincipalOrgResolver,
    val registerPhone: RegisterPushDeviceUseCase,
    val removePhone: DeletePushDeviceUseCase,
)
