// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.kernel.auth

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** How old a person's sign-in may be for a sensitive write from the console or the app (S2-54, web audit n°2 M3). */
val STEP_UP_WITHIN: Duration = 5.minutes

/** The stable error type asking the person to sign in again (`error.type`), declared in the contract. */
const val STEP_UP_REQUIRED: String = "step_up_required"

/**
 * A sensitive write from a sign-in older than [STEP_UP_WITHIN], or of unknown age: the console signs the person in
 * again (OAuth `prompt=login`, a fresh e-mail link), the app asks for its biometrics, then the write is sent again.
 *
 * @param action What the person was doing, in the message (« create a token »).
 */
class StepUpRequiredException(action: String) : OstromException(
    ErrorKind.FORBIDDEN,
    "Sign in again to $action: it needs a sign-in of less than ${STEP_UP_WITHIN.inWholeMinutes} minutes.",
    STEP_UP_REQUIRED,
)

/**
 * Refuses a sensitive write made from the console (a browser session) or the app when the person's last proof of who
 * they are ([AuthenticatedPrincipal.authenticatedAt]) is unknown or older than [STEP_UP_WITHIN]: a session left open
 * on a shared screen, or stolen, does not create a token, change a role or read a secret's place without a fresh
 * sign-in. An API token has no sign-in to renew: the token itself was created behind this check.
 *
 * @param action What the person is doing, for the message.
 * @param now The current time.
 * @throws StepUpRequiredException A 403 `step_up_required`.
 */
fun AuthenticatedPrincipal.requireRecentSignIn(action: String, now: Instant) {
    if (channel == PrincipalChannel.API_TOKEN) return
    val at = authenticatedAt
    if (at == null || now - at > STEP_UP_WITHIN) throw StepUpRequiredException(action)
}

/** The server's clock (the one the identity module stamps a sign-in with), set by the kernel's HTTP setup. */
internal val stepUpClockKey: AttributeKey<Clock> = AttributeKey("ostrom.stepUpClock")

/**
 * [requireRecentSignIn] on the caller, against the server's clock.
 *
 * @param action What the person is doing, for the message.
 * @throws StepUpRequiredException A 403 `step_up_required`.
 */
fun ApplicationCall.requireRecentSignIn(action: String) {
    val clock = application.attributes.getOrNull(stepUpClockKey) ?: Clock.System
    requirePrincipal().requireRecentSignIn(action, clock.now())
}
