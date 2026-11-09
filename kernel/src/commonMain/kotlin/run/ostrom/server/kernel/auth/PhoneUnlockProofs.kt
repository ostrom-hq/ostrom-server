// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.auth

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * How old the phone's unlock bound to a request may be for the yes to it: one biometric, one yes, at once
 * (Q-APP-STEPUP-RISK, app security audit A-S1). A no keeps the five minutes of [STEP_UP_WITHIN].
 */
val BOUND_UNLOCK_WITHIN: Duration = 60.seconds

/**
 * The proofs the phone app gives for a high-risk yes: an unlock (`POST /api/v1/auth/mobile/unlock`) that names the
 * approval request it is for. Owned by the identity module, which records the unlocks; read by the gates module,
 * which takes a yes from the app only with one.
 */
fun interface PhoneUnlockProofs {
    /**
     * Uses the app token's unlock for [requestId], in one statement: true only when its last unlock named this request
     * and is not older than [since]; the unlock serves no other yes after it, whatever this one's outcome.
     *
     * @param credentialId The app's token ([AuthenticatedPrincipal.credentialId]).
     * @param requestId The approval request the yes answers.
     * @param since The oldest unlock taken.
     *
     * @return True when the proof was there and is now used.
     */
    suspend fun consume(credentialId: String, requestId: String, since: Instant): Boolean
}

/** No unlock proves anything: the default until the identity module is registered, so no phone yes slips through. */
class RejectingPhoneUnlockProofs : PhoneUnlockProofs {
    override suspend fun consume(credentialId: String, requestId: String, since: Instant): Boolean = false
}
