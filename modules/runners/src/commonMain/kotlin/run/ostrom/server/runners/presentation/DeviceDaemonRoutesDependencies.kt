// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.runners.domain.DeviceDaemonUseCase
import run.ostrom.server.runners.domain.RefuseRemoteHostUseCase

/**
 * What the device daemon routes need (TeamCode's `DeviceDaemonChannel`).
 *
 * @property daemon The daemon channel.
 * @property refuseHost Refuses an order.
 * @property orgResolver The member a request acts for.
 * @property hosts Shows a refused session with its host.
 */
data class DeviceDaemonRoutesDependencies(
    val daemon: DeviceDaemonUseCase,
    val refuseHost: RefuseRemoteHostUseCase,
    val orgResolver: PrincipalOrgResolver,
    val hosts: RemoteHostViews,
)
