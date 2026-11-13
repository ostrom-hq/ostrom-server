// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.fakes

import run.ostrom.server.runners.domain.RunnerWorkSignals
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlin.uuid.Uuid

/**
 * [RunnerWorkSignals] a test drives: [arrived] is recorded, and a waiting heartbeat wakes once per [wake].
 *
 * @param room Whether a heartbeat may wait; false answers it at once.
 */
class FakeRunnerWorkSignals(var room: Boolean = true) : RunnerWorkSignals {
    /** The organizations told of new work, in order. */
    val arrivals = mutableListOf<Uuid>()

    /** The heartbeats that started waiting. */
    var waits = 0
        private set

    private val wakes = Channel<Unit>(Channel.UNLIMITED)

    /** Wakes the next waiting heartbeat. */
    fun wake() {
        wakes.trySend(Unit)
    }

    override fun arrived(orgId: Uuid) {
        arrivals += orgId
    }

    override suspend fun await(orgId: Uuid): Boolean {
        if (!room) return false
        waits++
        wakes.receive()
        return true
    }

    override suspend fun watch(): Nothing = awaitCancellation()
}
