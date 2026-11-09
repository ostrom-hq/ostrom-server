// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.governance.domain

import kotlin.time.Duration

/** Wakes the reads waiting on a confirmation once it is answered on this instance (a shortcut, never the truth). */
interface ConfirmationWaits {
    /**
     * Waits for [id] to change on this instance, at most [within]; holds no database connection.
     *
     * @param id The confirmation.
     * @param within The longest wait.
     */
    suspend fun await(id: String, within: Duration)

    /**
     * Says [id] changed.
     *
     * @param id The confirmation.
     */
    fun changed(id: String)
}
