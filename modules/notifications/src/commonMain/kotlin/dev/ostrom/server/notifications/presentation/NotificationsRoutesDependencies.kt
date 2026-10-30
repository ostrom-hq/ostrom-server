// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.notifications.presentation

import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.notifications.domain.PushDevicesUseCase

/**
 * What the notifications routes need.
 *
 * @property actors Resolves callers (the organization comes from the credential).
 * @property devices Registers and removes phones.
 */
data class NotificationsRoutesDependencies(val actors: GateActorResolver, val devices: PushDevicesUseCase)
