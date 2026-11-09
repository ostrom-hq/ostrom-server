// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.governance.infrastructure

import run.ostrom.server.governance.domain.ConfirmationWaits
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * [ConfirmationWaits] within this instance: an answer given here wakes the reads waiting here at once; one given on
 * another instance is seen at the next look at the database (`ConfirmationRules.LOOK_EVERY`).
 */
class LocalConfirmationWaits : ConfirmationWaits {
    private val changes = MutableSharedFlow<String>(extraBufferCapacity = BUFFER)

    override suspend fun await(id: String, within: Duration) {
        withTimeoutOrNull(within) { changes.first { it == id } }
    }

    override fun changed(id: String) {
        changes.tryEmit(id)
    }

    private companion object {
        // A full buffer drops a wake-up, never an answer: the waiting read looks again within a second.
        const val BUFFER = 256
    }
}
