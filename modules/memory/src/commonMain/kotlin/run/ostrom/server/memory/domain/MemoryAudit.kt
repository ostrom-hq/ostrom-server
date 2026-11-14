// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.domain

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import kotlin.uuid.Uuid

/** Writes the memory's changes to the organization's audit log: the entry's id and facts, never a title or content. */
fun interface MemoryAudit {
    /**
     * Records a change, after it is made.
     *
     * @param orgId The organization.
     * @param actor Who made it: the caller's credential, or [MEMORY_ACTOR] for the settling pass.
     * @param event `memory.created`, `memory.updated`, `memory.forgotten`, `memory.proposed`…
     * @param id The entry.
     * @param facts Extra facts: the scope, the origin, who answered.
     */
    suspend fun record(
        orgId: Uuid,
        actor: AuthenticatedPrincipal,
        event: String,
        id: Uuid,
        vararg facts: Pair<String, String>,
    )
}

/** Records a change [caller] made. */
suspend fun MemoryAudit.record(caller: MemoryCaller, event: String, id: Uuid, vararg facts: Pair<String, String>) =
    record(caller.orgId, caller.principal, event, id, *facts)
