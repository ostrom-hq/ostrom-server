// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.presentation

import run.ostrom.server.memory.domain.MemoryService

/**
 * What the memory routes need.
 *
 * @property memory The memory's rules.
 * @property callers Who a request acts for.
 */
data class MemoryRoutesDependencies(val memory: MemoryService, val callers: MemoryCallers)
