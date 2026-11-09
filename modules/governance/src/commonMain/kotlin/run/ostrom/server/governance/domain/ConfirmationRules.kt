// SPDX-License-Identifier: AGPL-3.0-only
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

    /** Who asks for and answers a governance change. */
    val MANAGERS: Set<OrgRole> = setOf(OrgRole.OWNER, OrgRole.ADMIN)
}
