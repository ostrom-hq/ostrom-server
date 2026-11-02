// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.presentation

import dev.ostrom.server.identity.domain.CreateServiceKeyUseCase
import dev.ostrom.server.identity.domain.DeleteServiceKeyUseCase
import dev.ostrom.server.identity.domain.ListDevicesUseCase
import dev.ostrom.server.identity.domain.ListServiceKeysUseCase
import dev.ostrom.server.identity.domain.SignOutDeviceUseCase
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver

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
