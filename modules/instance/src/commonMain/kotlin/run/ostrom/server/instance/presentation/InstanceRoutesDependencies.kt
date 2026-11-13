// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.instance.presentation

import run.ostrom.server.instance.domain.GetCurrentSigningKeyUseCase
import run.ostrom.server.instance.domain.GetOrCreateInstanceUseCase
import run.ostrom.server.instance.domain.ListSigningKeysUseCase

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
