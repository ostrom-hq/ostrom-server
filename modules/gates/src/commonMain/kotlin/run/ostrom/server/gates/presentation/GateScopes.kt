// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gates.presentation

import run.ostrom.server.gates.domain.models.StepUpRequiredException
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/**
 * The scopes the gates routes require (SECURITY-AUDIT S2-03, S2-31). A user's browser session ([ALL_USER]) holds them
 * all; any other credential holds only those listed on it. An empty set opens nothing.
 */
object GateScopes {
    /** A user's browser session: every right of the user (granted by identity to session cookies only). */
    const val ALL_USER: String = "user:*"

    /** One agent session's token: it asks and uses the gates of its own session. */
    const val AGENT_SESSION: String = "agent:session"

    /** A CI or runner key: it asks a gate and reads it, never uses a yes (a session's) nor answers one. */
    const val ASK: String = "gates:ask"

    /** Reads gates and approvals. */
    const val READ: String = "gates:read"

    /**
     * Answers an approval. Identity grants it only where a person just proved presence (a browser session with a
     * recent passkey, the mobile app after Face ID), never to a CLI, device or relay token (S2-31).
     */
    const val ANSWER: String = "gates:answer"

    /** Who opens a gate. */
    val asking: Set<String> = setOf(AGENT_SESSION, ASK)

    /** Who moves a yes: reserve, consume, release. Only a session holds a launch to bind it to. */
    val using: Set<String> = setOf(AGENT_SESSION)

    /** Who reads a gate. */
    val readingGates: Set<String> = setOf(ALL_USER, AGENT_SESSION, ASK, READ)

    /** Who reads approvals. [READ] last: a refusal names the reading scope, never [ANSWER] (LOGIN-009). */
    val readingApprovals: Set<String> = setOf(ALL_USER, ANSWER, READ)

    /** Who answers. */
    val answering: Set<String> = setOf(ALL_USER, ANSWER)
}

/**
 * Refuses a credential that holds none of the accepted scopes.
 *
 * @param accepted The scopes that open the route, the one a refused credential should ask for last.
 * @throws OstromException A 403 that names the last accepted scope.
 */
fun AuthenticatedPrincipal.requireAnyScope(accepted: Set<String>) {
    if (scopes.none { it in accepted }) {
        throw OstromException(ErrorKind.FORBIDDEN, "This credential does not allow ${accepted.last()}.")
    }
}

/**
 * Refuses an answer from anything but a person who holds [GateScopes.answering], in the browser or in the unlocked
 * app (S2-31, S2-04): a device, an agent or a service never says yes, nor a person's API token, which the agent of
 * that person can read.
 *
 * @throws OstromException A 403.
 */
fun AuthenticatedPrincipal.requireAnswering() {
    val refused = when {
        kind != PrincipalKind.USER -> OstromException(ErrorKind.FORBIDDEN, "Only a person answers a gate.")
        channel != PrincipalChannel.BROWSER && channel != PrincipalChannel.MOBILE_APP ->
            OstromException(ErrorKind.FORBIDDEN, "A yes is given in the browser or in the app.")
        // The app answers within minutes of the phone's biometric unlock: a locked app is told to unlock, then answer.
        channel == PrincipalChannel.MOBILE_APP && scopes.none { it in GateScopes.answering } ->
            StepUpRequiredException()
        else -> null
    }
    if (refused != null) throw refused
    requireAnyScope(GateScopes.answering)
}
