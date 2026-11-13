// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.domain.models

import run.ostrom.server.kernel.auth.BOUND_UNLOCK_WITHIN
import run.ostrom.server.kernel.auth.STEP_UP_WITHIN
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * The fresh proof a yes needs (SECURITY-AUDIT S2-54, rule of 30/10 07:20): a high-risk action is approved only by a
 * person who signed in at most [WITHIN] ago (OAuth `max_age`/`prompt=login`, a fresh e-mail link) or unlocked the app
 * with biometrics; a low-risk one would make do with the browser session. Every action is high risk until the server
 * measures its diff itself (the diff the developer's machine sends is not proof, S2-05): no yes is low today. So a yes
 * from the app always takes an unlock bound to its own request, at most [BOUND_WITHIN] old and used once
 * (Q-APP-STEPUP-RISK): one biometric is one yes, never five minutes of them. A no keeps [WITHIN].
 */
object StepUp {
    /** How old the sign-in of a yes may be. */
    val WITHIN: Duration = STEP_UP_WITHIN

    /** How old the app's unlock bound to the request may be for a yes from the app. */
    val BOUND_WITHIN: Duration = BOUND_UNLOCK_WITHIN

    /**
     * Refuses a yes whose sign-in is unknown or older than [WITHIN], or a yes from the app without its bound unlock;
     * a no never needs either.
     *
     * @param answer The answer.
     * @param authenticatedAt When the person last proved who they are.
     * @param now The current time.
     * @param bound For a yes from the app, whether its unlock bound to this request was there and is now used; null
     *   for any other caller.
     * @throws StepUpRequiredException A 403 asking to sign in again.
     * @throws BoundUnlockRequiredException A 403 asking the app to unlock again for this request.
     */
    fun check(answer: Answer, authenticatedAt: Instant?, now: Instant, bound: Boolean?) {
        if (answer == Answer.NO) return
        if (authenticatedAt == null || now - authenticatedAt > WITHIN) throw StepUpRequiredException()
        if (bound == false) throw BoundUnlockRequiredException()
    }
}
