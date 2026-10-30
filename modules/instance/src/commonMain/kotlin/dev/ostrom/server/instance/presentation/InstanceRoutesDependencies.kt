// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.instance.presentation

import dev.ostrom.server.instance.domain.GetCurrentSigningKeyUseCase
import dev.ostrom.server.instance.domain.GetOrCreateInstanceUseCase
import dev.ostrom.server.instance.domain.ListSigningKeysUseCase

/**
 * What the instance routes need.
 *
 * @property getOrCreateInstance Reads the instance.
 * @property getCurrentSigningKey Reads the current key (recorded at start).
 * @property listSigningKeys Lists the keys.
 */
data class InstanceRoutesDependencies(
    val getOrCreateInstance: GetOrCreateInstanceUseCase,
    val getCurrentSigningKey: GetCurrentSigningKeyUseCase,
    val listSigningKeys: ListSigningKeysUseCase,
)
