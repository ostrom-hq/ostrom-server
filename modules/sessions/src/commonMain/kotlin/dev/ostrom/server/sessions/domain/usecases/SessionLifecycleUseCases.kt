// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions.domain.usecases

import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.sessions.domain.models.EndSession
import dev.ostrom.server.sessions.domain.models.Opening
import dev.ostrom.server.sessions.domain.models.Reopened
import dev.ostrom.server.sessions.domain.models.SeenItem
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.sessions.domain.models.SessionAccess
import dev.ostrom.server.sessions.domain.models.SessionClosedException
import dev.ostrom.server.sessions.domain.models.SessionNotFoundException
import dev.ostrom.server.sessions.domain.models.SessionResumedElsewhereException
import dev.ostrom.server.sessions.domain.models.SessionView
import dev.ostrom.server.sessions.domain.models.StartSession
import dev.ostrom.server.sessions.domain.repositories.SessionsRepository
import dev.ostrom.server.sessions.domain.repositories.SessionsTransaction
import dev.ostrom.server.sessions.domain.usecases.SessionSegments.Companion.clip
import kotlin.time.Clock

/** Starts a session or finds the open segment of the same launch (hook `SessionStart`). */
interface StartSessionUseCase {
    /**
     * Starts or finds the segment.
     *
     * @param actor The caller: its person is the session's, never a name in the body.
     * @param start What the launch sends.
     * @return The open segment.
     */
    suspend operator fun invoke(actor: GateActor, start: StartSession): Session
}

/**
 * [StartSessionUseCase] on [SessionSegments].
 *
 * @param repository The sessions.
 * @param segments Opens the segments.
 */
class StartSessionUseCaseImpl(
    private val repository: SessionsRepository,
    private val segments: SessionSegments,
) : StartSessionUseCase {
    override suspend fun invoke(actor: GateActor, start: StartSession): Session {
        val bounded = SessionSegments.bounded(start.copy(launchId = actor.launchId ?: start.launchId))
        return repository.transaction(actor.orgId) {
            lockRoot(bounded.id)
            segments.open(this, actor.orgId, SessionAccess.person(actor), bounded, Opening.of(actor, null))
        }
    }
}

/** Ends a segment for its launcher (hook `SessionEnd`, or the launcher's net). */
interface EndSessionUseCase {
    /**
     * Ends the segment; the server signs its receipt.
     *
     * @param actor The caller, the session's person.
     * @param id The segment.
     * @param end What the end says.
     * @return The ended segment.
     */
    suspend operator fun invoke(actor: GateActor, id: String, end: EndSession): Session

    /**
     * Ends the segment as [invoke] does, and gives its view (`POST /sessions/{id}/end` answers it): from the gates and
     * the receipt the end just signed with, read again only when another end signed them.
     *
     * @param actor The caller, the session's person.
     * @param id The segment.
     * @param end What the end says.
     * @return The ended segment, its gates and its receipt.
     */
    suspend fun view(actor: GateActor, id: String, end: EndSession): SessionView
}

/**
 * [EndSessionUseCase]: the owner check, then [CloseSessionUseCase] on the segment the check read.
 *
 * @param repository The sessions.
 * @param close Ends and signs.
 * @param views The view of a segment.
 */
class EndSessionUseCaseImpl(
    private val repository: SessionsRepository,
    private val close: CloseSessionUseCase,
    private val views: GetSessionUseCase,
) : EndSessionUseCase {
    override suspend fun invoke(actor: GateActor, id: String, end: EndSession): Session = closed(actor, id, end).session

    override suspend fun view(actor: GateActor, id: String, end: EndSession): SessionView {
        val closed = closed(actor, id, end)
        val signed = closed.signed
        return if (signed == null) {
            views.view(closed.session)
        } else {
            views.viewOf(closed.session, signed.gates, signed.receipt)
        }
    }

    private suspend fun closed(actor: GateActor, id: String, end: EndSession): ClosedSession {
        val session = repository.get(actor.orgId, id) ?: throw SessionNotFoundException()
        SessionAccess.ensureOwner(actor, session)
        return close.close(actor.orgId, session, end)
    }
}

/** The launcher's sign of life, every 20 s. */
interface SessionAliveUseCase {
    /**
     * Records a sign of life.
     *
     * @param actor The caller, the session's person.
     * @param id The segment.
     * @return The segment: when it ended, its launcher reads why ([Session.lost]: reopen a segment).
     */
    suspend operator fun invoke(actor: GateActor, id: String): Session
}

/**
 * [SessionAliveUseCase] on the repository.
 *
 * @param repository The sessions.
 * @param clock The time.
 */
class SessionAliveUseCaseImpl(
    private val repository: SessionsRepository,
    private val clock: Clock,
) : SessionAliveUseCase {
    override suspend fun invoke(actor: GateActor, id: String): Session = repository.transaction(actor.orgId) {
        val session = lock(id) ?: throw SessionNotFoundException()
        SessionAccess.ensureOwner(actor, session)
        if (session.endedAt != null) return@transaction session
        session.copy(lastSeenAt = clock.now()).also { update(it) }
    }
}

/** Reopens a segment for the launcher of a closed one (CORRECTIFS-3: the machine slept longer than the sweep). */
interface ReopenSessionUseCase {
    /**
     * Opens the next segment, chained to the closed one, with the same launch, package and person. Idempotent: the
     * segment already reopened for this launch is returned. A yes given in the closed segment does not pass to the new
     * one.
     *
     * @param actor The caller, the session's person.
     * @param id The closed segment.
     * @return The open segment, and whether it was opened now.
     */
    suspend operator fun invoke(actor: GateActor, id: String): Reopened
}

/**
 * [ReopenSessionUseCase] on [SessionSegments].
 *
 * @param repository The sessions.
 * @param segments Opens the segments.
 */
class ReopenSessionUseCaseImpl(
    private val repository: SessionsRepository,
    private val segments: SessionSegments,
) : ReopenSessionUseCase {
    override suspend fun invoke(actor: GateActor, id: String): Reopened {
        val found = repository.get(actor.orgId, id) ?: throw SessionNotFoundException()
        SessionAccess.ensureOwner(actor, found)
        return repository.transaction(actor.orgId) { reopen(this, actor, found.rootId, id) }
    }

    // The root, then its segments: the order every start takes its locks in.
    private suspend fun reopen(tx: SessionsTransaction, actor: GateActor, rootId: String, id: String): Reopened {
        tx.lockRoot(rootId)
        val all = tx.segments(rootId)
        val closed = all.first { it.id == id }
        val last = all.last()
        return when {
            closed.endedAt == null -> Reopened(closed, reopened = false)
            // The same launch (or, outside the launcher, no launch on either side): the segment already reopened.
            last.endedAt == null && last.launchId == closed.launchId -> Reopened(last, reopened = false)
            last.endedAt == null -> throw SessionResumedElsewhereException(closed.rootId, last.id)
            else -> {
                val opening = Opening.of(actor, reasonAfter(last))
                Reopened(segments.open(tx, actor.orgId, closed.dev, nextOf(closed), opening), true)
            }
        }
    }

    private fun nextOf(closed: Session) = StartSession(
        id = closed.rootId,
        launchId = closed.launchId,
        repo = closed.repo,
        realRepo = closed.realRepo,
        cwd = closed.cwd,
        agent = closed.agent,
        harnessVersion = closed.harnessVersion,
        packageDigest = closed.packageDigest,
        model = closed.model,
    )

    private fun reasonAfter(last: Session): String? =
        "reopened: the launcher came back after \"${last.source ?: "ended"}\"".clip(SessionSegments.TEXT)
}

/** Records a skill or sub-agent of the session package the agent opened. */
interface ReportSeenUseCase {
    /**
     * Records it once (the same kind and name twice is one).
     *
     * @param actor The caller, the session's person.
     * @param id The segment, open.
     * @param kind `skill` or `agent`.
     * @param name Its name.
     * @param via `hook` or `mcp`.
     */
    suspend operator fun invoke(actor: GateActor, id: String, kind: String, name: String, via: String)
}

/**
 * [ReportSeenUseCase] on the repository.
 *
 * @param repository The sessions.
 * @param clock The time.
 */
class ReportSeenUseCaseImpl(
    private val repository: SessionsRepository,
    private val clock: Clock,
) : ReportSeenUseCase {
    override suspend fun invoke(actor: GateActor, id: String, kind: String, name: String, via: String) {
        repository.transaction(actor.orgId) {
            val session = lock(id) ?: throw SessionNotFoundException()
            SessionAccess.ensureOwner(actor, session)
            if (session.endedAt != null) throw SessionClosedException(session)
            val item = SeenItem(kind.take(SHORT), name.take(SessionSegments.TEXT), via.take(SHORT), clock.now())
            val known = session.seen.any { it.kind == item.kind && it.name == item.name }
            if (!known && session.seen.size < MAX_SEEN) update(session.copy(seen = session.seen + item))
        }
    }

    private companion object {
        const val SHORT = 32
        const val MAX_SEEN = 200
    }
}
