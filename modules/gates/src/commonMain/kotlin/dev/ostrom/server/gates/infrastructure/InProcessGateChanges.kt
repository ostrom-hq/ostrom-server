// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.infrastructure

import dev.ostrom.server.gates.domain.services.GateChanges
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.uuid.Uuid

/**
 * [GateChanges] inside one instance: every waiting read listens to one shared stream of changed gates. A burst larger
 * than the buffer drops the oldest wake-ups, which the waiting reads then see at their next look at the database.
 */
class InProcessGateChanges : GateChanges {
    private val changes =
        MutableSharedFlow<Uuid>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override fun changed(gateId: Uuid) {
        changes.tryEmit(gateId)
    }

    override suspend fun await(gateId: Uuid, within: Duration) {
        withTimeoutOrNull(within) { changes.first { it == gateId } }
    }

    private companion object {
        const val BUFFER = 256
    }
}
