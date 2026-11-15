// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.runners.domain.SessionState.CREATING
import run.ostrom.server.runners.domain.SessionState.FAILED
import run.ostrom.server.runners.domain.SessionState.PAUSED
import run.ostrom.server.runners.domain.SessionState.PAUSING
import run.ostrom.server.runners.domain.SessionState.RESUMING
import run.ostrom.server.runners.domain.SessionState.RUNNING
import run.ostrom.server.runners.domain.SessionState.TERMINATED
import run.ostrom.server.runners.domain.SessionState.TRANSFERRING

/**
 * The transitions a session may make. Every write of a state goes through [requireTransition], so a defect in the
 * reconciler can never put a session in a state it cannot leave.
 */
object SessionStateMachine {
    private val allowed: Map<SessionState, Set<SessionState>> = mapOf(
        CREATING to setOf(RUNNING, PAUSED, FAILED, TERMINATED),
        RUNNING to setOf(PAUSING, PAUSED, TERMINATED),
        PAUSING to setOf(PAUSED, RUNNING, TERMINATED),
        // The owner changes only at a paused boundary: no execution at all.
        PAUSED to setOf(RESUMING, TERMINATED, TRANSFERRING),
        TRANSFERRING to setOf(PAUSED, RESUMING, TERMINATED),
        RESUMING to setOf(RUNNING, PAUSED, FAILED, TERMINATED),
        TERMINATED to emptySet(),
        FAILED to setOf(TERMINATED),
    )

    /**
     * Whether a session may move from one state to another. Staying in the same state is always allowed.
     *
     * @param from The current state.
     * @param to The next state.
     *
     * @return True when the move is allowed.
     */
    fun canMove(from: SessionState, to: SessionState): Boolean = from == to || to in allowed.getValue(from)

    /**
     * Whether a remote session may move from one state to another: as any session, and its host started again
     * replaces its running execution (RUNNING to RESUMING, [HostRemoteSessionUseCase]). A pod's session never does.
     *
     * @param from The current state.
     * @param to The next state.
     *
     * @return True when the move is allowed.
     */
    fun canMoveRemote(from: SessionState, to: SessionState): Boolean =
        canMove(from, to) || (from == RUNNING && to == RESUMING)

    /**
     * Checks a move.
     *
     * @param from The current state.
     * @param to The next state.
     *
     * @throws IllegalStateException When the move is not allowed: a defect, never a refusal a client causes.
     */
    fun requireTransition(from: SessionState, to: SessionState) {
        check(canMove(from, to)) { "A cloud session cannot move from ${from.wire} to ${to.wire}." }
    }

    /**
     * Checks a move of a remote session ([canMoveRemote]).
     *
     * @param from The current state.
     * @param to The next state.
     *
     * @throws IllegalStateException When the move is not allowed: a defect, never a refusal a client causes.
     */
    fun requireRemoteTransition(from: SessionState, to: SessionState) {
        check(canMoveRemote(from, to)) { "A remote session cannot move from ${from.wire} to ${to.wire}." }
    }
}
