// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Who a runner call's credential names: the execution it acts for, or null when it is not one of this resolver's.
 * The execution's runner token names its execution alone; the owner's sign-in on a remote session's device
 * ([OwnerLoginPrincipalResolver]) names the session only with the claim of the call, as TeamCode's.
 */
fun interface RunnerPrincipalResolver {
    /**
     * @param credential The bearer credential, as presented.
     * @return The execution, or null.
     */
    suspend fun resolve(credential: String): RunnerExecution?

    /**
     * The execution a call claims, once its body is read: by default the one the credential alone names.
     *
     * @param credential The bearer credential, as presented.
     * @param sessionId The session the call claims.
     * @param epoch The execution it claims.
     * @return The execution, or null.
     */
    suspend fun resolve(credential: String, sessionId: Uuid, epoch: Long): RunnerExecution? = resolve(credential)

    /**
     * Whether the credential is one of this resolver's, whatever execution it names: a call it knows but fences is
     * `409 execution_fenced`, one no resolver knows `401`.
     *
     * @param credential The bearer credential, as presented.
     * @return Whether this resolver knows it.
     */
    suspend fun knows(credential: String): Boolean = resolve(credential) != null
}

/**
 * [RunnerPrincipalResolver] on the runner token: the current execution of the session that holds it.
 *
 * @param credentials Finds the session of the presented token (the runner token, or the pod's).
 */
class RunnerTokenPrincipalResolver(
    private val credentials: RunnerCredentials,
) : RunnerPrincipalResolver {
    // A pod token of another execution is no credential of this resolver: the next resolvers are still asked, and
    // the use case fences the call when none knows it.
    override suspend fun resolve(credential: String): RunnerExecution? = try {
        credentials.session(credential)?.let { RunnerExecution(it.orgId, it.id, it.epoch) }
    } catch (_: ExecutionFencedException) {
        null
    }
}

/** Fences a runner call on the execution it claims: the one place every runner channel checks its caller. */
interface AuthenticateRunnerUseCase {
    /**
     * Who the credential names, before the call's body is read (S2-46).
     *
     * @param credential The bearer credential.
     * @return The execution it acts for.
     * @throws ExecutionFencedException When no resolver knows the credential.
     */
    suspend fun resolve(credential: String): RunnerExecution

    /**
     * @param credential The bearer credential.
     * @param sessionId The session the runner claims.
     * @param epoch The execution it claims.
     * @return The session, at that execution.
     * @throws ExecutionFencedException When no resolver knows the credential, or it names another execution.
     */
    suspend operator fun invoke(credential: String, sessionId: Uuid, epoch: Long): CloudSession
}

/**
 * [AuthenticateRunnerUseCase] over [resolvers], asked in order: the first that knows the credential decides.
 *
 * @param resolvers Who a credential may name.
 * @param repository The sessions, read once the claim matched.
 */
class AuthenticateRunnerUseCaseImpl(
    private val resolvers: List<RunnerPrincipalResolver>,
    private val repository: CloudSessionsRepository,
) : AuthenticateRunnerUseCase {
    override suspend fun resolve(credential: String): RunnerExecution =
        resolvers.firstNotNullOfOrNull { it.resolve(credential) } ?: throw ExecutionFencedException()

    override suspend fun invoke(credential: String, sessionId: Uuid, epoch: Long): CloudSession {
        val execution = resolvers.firstNotNullOfOrNull { it.resolve(credential, sessionId, epoch) }
            ?.takeIf { it.sessionId == sessionId && it.epoch == epoch } ?: throw ExecutionFencedException()
        return repository.get(execution.orgId, sessionId)?.takeIf { it.epoch == epoch }
            ?: throw ExecutionFencedException()
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
        if (session.mode == SessionMode.CONVERSATION) {
            throw CloudConversationRules.conflict(
                "conversation_session",
                "This session's agent runs headless and has no terminal: read its transcript and send it messages.",
            )
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
 * @param maxTracked The sessions remembered before the ones quiet for an [interval] are forgotten.
 */
class TerminalActivity(
    private val record: RecordCloudSessionActivityUseCase,
    private val clock: Clock,
    private val interval: kotlin.time.Duration = DEFAULT_INTERVAL,
    private val maxTracked: Int = MAX_TRACKED,
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
            if (last.size > maxTracked) last.entries.removeAll { now - it.value >= interval }
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
