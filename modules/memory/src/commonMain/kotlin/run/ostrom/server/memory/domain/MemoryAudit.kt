// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.domain

import kotlin.uuid.Uuid

/** Writes the memory's changes to the organization's audit log: the entry's id and facts, never a title or content. */
fun interface MemoryAudit {
    /**
     * Records a change, after it is made.
     *
     * @param caller Who made it.
     * @param event `memory.created`, `memory.updated`, `memory.forgotten`…
     * @param id The entry.
     * @param facts Extra facts: the scope, the origin.
     */
    suspend fun record(caller: MemoryCaller, event: String, id: Uuid, vararg facts: Pair<String, String>)
}
