// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.presentation

import run.ostrom.server.identity.domain.CreateServiceKeyUseCase
import run.ostrom.server.identity.domain.DeleteServiceKeyUseCase
import run.ostrom.server.identity.domain.ListDevicesUseCase
import run.ostrom.server.identity.domain.ListServiceKeysUseCase
import run.ostrom.server.identity.domain.SignOutDeviceUseCase
import run.ostrom.server.kernel.auth.PrincipalOrgResolver

/**
 * The dependencies of the service-key and device routes.
 *
 * @property orgs Resolves the organization a request acts in and the caller's role there.
 * @property createServiceKey Creates a service key.
 * @property listServiceKeys Lists them.
 * @property deleteServiceKey Deletes one.
 * @property listDevices Lists the caller's workstations.
 * @property signOutDevice Signs one out.
 */
data class KeysAndDevicesRoutesDependencies(
    val orgs: PrincipalOrgResolver,
    val createServiceKey: CreateServiceKeyUseCase,
    val listServiceKeys: ListServiceKeysUseCase,
    val deleteServiceKey: DeleteServiceKeyUseCase,
    val listDevices: ListDevicesUseCase,
    val signOutDevice: SignOutDeviceUseCase,
)
