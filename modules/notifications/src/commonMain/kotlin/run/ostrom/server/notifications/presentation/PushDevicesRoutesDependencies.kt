// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications.presentation

import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.notifications.domain.DeletePushDeviceUseCase
import run.ostrom.server.notifications.domain.ListPushDevicesUseCase
import run.ostrom.server.notifications.domain.RegisterPushDeviceUseCase

/**
 * What the phones routes need.
 *
 * @property orgs The member the caller acts for.
 * @property registerPhone Registers a phone.
 * @property listPhones Lists the caller's phones.
 * @property removePhone Removes one.
 */
data class PushDevicesRoutesDependencies(
    val orgs: PrincipalOrgResolver,
    val registerPhone: RegisterPushDeviceUseCase,
    val removePhone: DeletePushDeviceUseCase,
    val listPhones: ListPushDevicesUseCase,
)
