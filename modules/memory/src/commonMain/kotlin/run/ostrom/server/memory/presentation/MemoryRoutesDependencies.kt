// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.presentation

import run.ostrom.server.memory.domain.MemoryCurationService
import run.ostrom.server.memory.domain.MemoryProposalsService
import run.ostrom.server.memory.domain.MemoryService

/**
 * What the memory routes need.
 *
 * @property memory The memory's rules.
 * @property proposals The agents' proposals for the organization's memory.
 * @property curation The curation, its reviews and the superseded entries.
 * @property callers Who a request acts for.
 */
data class MemoryRoutesDependencies(
    val memory: MemoryService,
    val proposals: MemoryProposalsService,
    val curation: MemoryCurationService,
    val callers: MemoryCallers,
)
