// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.gates.domain.models

import dev.ostrom.server.kernel.auth.STEP_UP_WITHIN
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The fresh proof a yes needs (SECURITY-AUDIT S2-54, rule of 30/10 07:20): a high-risk action is approved only by a
 * person who signed in at most [WITHIN] ago (OAuth `max_age`/`prompt=login`, a fresh e-mail link) or unlocked the app
 * with biometrics; a low-risk one would make do with the browser session. Every action is high risk until the server
 * measures its diff itself (the diff the developer's machine sends is not proof, S2-05): no yes is low today.
 */
object StepUp {
    /** How old the sign-in of a yes may be. */
    val WITHIN: Duration = STEP_UP_WITHIN

    /**
     * Refuses a yes whose sign-in is unknown or older than [WITHIN]; a no never needs it.
     *
     * @param answer The answer.
     * @param authenticatedAt When the person last proved who they are.
     * @param now The current time.
     * @throws StepUpRequiredException A 403 asking to sign in again.
     */
    fun check(answer: Answer, authenticatedAt: Instant?, now: Instant) {
        if (answer == Answer.NO) return
        if (authenticatedAt == null || now - authenticatedAt > WITHIN) throw StepUpRequiredException()
    }
}
