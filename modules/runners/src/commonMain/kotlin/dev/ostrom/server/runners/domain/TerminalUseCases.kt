// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Checks a runner's token against the execution it claims, for the terminal channel. */
interface AuthenticateRunnerUseCase {
    /**
     * Authenticates the runner.
     *
     * @param token The runner token.
     * @param sessionId The session the runner claims.
     * @param epoch The execution it claims.
     *
     * @return The session.
     *
     * @throws RunnerUnauthenticatedException When no session has this token.
     * @throws ExecutionFencedException When the claim is not the token's current execution.
     */
    suspend operator fun invoke(token: String, sessionId: Uuid, epoch: Long): CloudSession
}

/**
 * @param repository The sessions.
 * @param tokens Hashes runner tokens.
 */
class AuthenticateRunnerUseCaseImpl(
    private val repository: CloudSessionsRepository,
    private val tokens: RunnerTokens,
) : AuthenticateRunnerUseCase {
    override suspend fun invoke(token: String, sessionId: Uuid, epoch: Long): CloudSession {
        val session = repository.findByRunnerTokenHash(tokens.hash(token)) ?: throw RunnerUnauthenticatedException()
        if (session.id != sessionId || session.epoch != epoch) throw ExecutionFencedException()
        return session
    }
}

/** Opens a member's terminal on one of their sessions. */
interface OpenTerminalUseCase {
    /**
     * Checks the caller may attach.
     *
     * @param caller The member.
     * @param sessionId The session.
     *
     * @return The session.
     *
     * @throws CloudSessionNotFoundException When the caller has no such session.
     * @throws CloudSessionStateException When the session is over.
     */
    suspend operator fun invoke(caller: Caller, sessionId: Uuid): CloudSession
}

/**
 * @param repository The sessions.
 */
class OpenTerminalUseCaseImpl(private val repository: CloudSessionsRepository) : OpenTerminalUseCase {
    override suspend fun invoke(caller: Caller, sessionId: Uuid): CloudSession {
        val session = repository.ownedBy(caller, sessionId)
        if (session.state.isFinal || session.desiredState == DesiredState.TERMINATED) {
            throw CloudSessionStateException("This cloud session is over: its terminal is closed.")
        }
        return session
    }
}

/**
 * A single-use ticket that opens a terminal from a browser, which cannot set a WebSocket's Authorization header.
 *
 * @property value The ticket, to pass as `?ticket=`.
 * @property expiresAt When it stops opening anything.
 */
data class TerminalTicket(val value: String, val expiresAt: Instant)

/** Issues and redeems terminal tickets. */
interface TerminalTickets {
    /**
     * Issues a ticket for one session.
     *
     * @param caller The member.
     * @param sessionId The session they may attach to.
     *
     * @return The ticket.
     */
    suspend fun issue(caller: Caller, sessionId: Uuid): TerminalTicket

    /**
     * Redeems a ticket, once.
     *
     * @param value The ticket.
     * @param sessionId The session the terminal is opened on.
     *
     * @return The member, or null when the ticket is unknown, used, expired or for another session.
     */
    suspend fun redeem(value: String, sessionId: Uuid): Caller?
}

/** Issues a terminal ticket after checking the caller may attach. */
interface IssueTerminalTicketUseCase {
    /**
     * Issues the ticket.
     *
     * @param caller The member.
     * @param sessionId The session.
     *
     * @return The ticket.
     */
    suspend operator fun invoke(caller: Caller, sessionId: Uuid): TerminalTicket
}

/**
 * @param openTerminal Checks the caller may attach.
 * @param tickets Issues the ticket.
 */
class IssueTerminalTicketUseCaseImpl(
    private val openTerminal: OpenTerminalUseCase,
    private val tickets: TerminalTickets,
) : IssueTerminalTicketUseCase {
    override suspend fun invoke(caller: Caller, sessionId: Uuid): TerminalTicket {
        openTerminal(caller, sessionId)
        return tickets.issue(caller, sessionId)
    }
}

/**
 * Records terminal input as activity at most once per [interval] and session, so typing keeps a session from the
 * idle pause without a database write per keystroke.
 *
 * @param record Records the activity.
 * @param clock The clock.
 * @param interval The shortest gap between two records of one session.
 */
class TerminalActivity(
    private val record: RecordCloudSessionActivityUseCase,
    private val clock: Clock,
    private val interval: kotlin.time.Duration = DEFAULT_INTERVAL,
) {
    private val mutex = Mutex()
    private val last = mutableMapOf<Uuid, Instant>()

    /**
     * Notes input on a session.
     *
     * @param sessionId The session.
     *
     * @return Whether it was recorded now.
     */
    suspend fun touch(sessionId: Uuid): Boolean {
        val now = clock.now()
        val due = mutex.withLock {
            val previous = last[sessionId]
            if (previous != null && now - previous < interval) return@withLock false
            last[sessionId] = now
            if (last.size > MAX_TRACKED) last.entries.removeAll { now - it.value >= interval }
            true
        }
        if (!due) return false
        record(sessionId)
        return true
    }

    /** Constants and readers of the enclosing type. */
    companion object {
        private val DEFAULT_INTERVAL = 30.seconds
        private const val MAX_TRACKED = 10_000
    }
}
