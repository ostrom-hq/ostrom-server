// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.governance.domain

import run.ostrom.server.kernel.auth.OrgRole
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The bounds of a governance confirmation (decision of 02/11/2026). */
object ConfirmationRules {
    /** How long a confirmation waits for its answer. */
    val LIFETIME: Duration = 15.minutes

    /** How old the sign-in of a confirmation may be, as a yes (S2-54). */
    val STEP_UP_WITHIN: Duration = 5.minutes

    /** How many confirmations one person may have waiting: a looping agent only blocks its own developer. */
    const val MAX_WAITING: Int = 10

    /** The longest wait of a read (`?wait=`). */
    val MAX_WAIT: Duration = 30.seconds

    /** How often a waiting read looks at the database for an answer given on another instance. */
    val LOOK_EVERY: Duration = 1.seconds

    /**
     * How long past its expiry a confirmed write may still be writing its result. A confirmation still confirmed with
     * no result after this was lost with its server (it stopped between the answer and the result): the sweep marks it
     * failed, `result_lost`, rather than leave it read as pending for ever (decision of 14/11/2026).
     */
    val RESULT_GRACE: Duration = 5.minutes

    /** The error of a confirmed write whose result was lost. */
    const val RESULT_LOST: String = "result_lost"

    /** What the console and the CLI say of it. */
    const val RESULT_LOST_MESSAGE: String = "The server stopped before it recorded whether this change was written: " +
        "check it, then ask again if it is not there."

    /** Who asks for and answers a governance change. */
    val MANAGERS: Set<OrgRole> = setOf(OrgRole.OWNER, OrgRole.ADMIN)
}
