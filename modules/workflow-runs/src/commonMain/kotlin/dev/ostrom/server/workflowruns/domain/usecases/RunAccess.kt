// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.usecases

import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.sessions.domain.models.NotYourSessionException
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.sessions.domain.models.SessionAccess
import dev.ostrom.server.sessions.domain.models.SessionClosedException
import dev.ostrom.server.workflowruns.domain.models.RunPackage
import dev.ostrom.server.workflowruns.domain.models.RunPackageMismatchException
import dev.ostrom.server.workflowruns.domain.models.RunPackageNotFoundException
import dev.ostrom.server.workflowruns.domain.models.RunSessionNotFoundException
import dev.ostrom.server.workflowruns.domain.models.SessionStillOpenException
import dev.ostrom.server.workflowruns.domain.services.RunPackages
import dev.ostrom.server.workflowruns.domain.services.RunSessions

/**
 * Who may act on the runs of a session: the sessions module's rules (audit A12), read once here.
 *
 * @param sessions The sessions.
 * @param packages The packages.
 */
class RunAccess(private val sessions: RunSessions, private val packages: RunPackages) {
    /**
     * The session a report is written for: only its person, and an agent token of its own launch, write there.
     *
     * @param actor The caller.
     * @param sessionId The session, or null for a report outside a session (a person's only).
     *
     * @return The session, or null without one.
     *
     * @throws RunSessionNotFoundException When the organization has no such session.
     * @throws NotYourSessionException When it is another person's, or another
     *   launch's; or when an agent token names no session.
     * @throws SessionClosedException When the session has ended: its receipt is signed, nothing is added to it.
     */
    suspend fun writing(actor: GateActor, sessionId: String?): Session? {
        if (sessionId != null) {
            return owned(actor, sessionId).also { if (it.endedAt != null) throw SessionClosedException(it) }
        }
        // An agent token acts inside its launch's sessions only: never outside one.
        if (actor.kind == ActorKind.AGENT_SESSION) throw NotYourSessionException()
        SessionAccess.person(actor)
        return null
    }

    /**
     * A session only its person, and an agent token of its own launch, write for.
     *
     * @param actor The caller.
     * @param sessionId The session.
     *
     * @return The session.
     *
     * @throws RunSessionNotFoundException When the organization has no such session.
     * @throws NotYourSessionException When it is another person's, or another launch's.
     */
    suspend fun owned(actor: GateActor, sessionId: String): Session {
        val session = sessions.find(actor.orgId, sessionId) ?: throw RunSessionNotFoundException()
        SessionAccess.ensureOwner(actor, session)
        return session
    }

    /**
     * An ended session of the caller's: its workflow receipt is signed once, with everything it reported.
     *
     * @param actor The caller.
     * @param sessionId The session.
     *
     * @return The session.
     *
     * @throws SessionStillOpenException When it has not ended.
     */
    suspend fun ended(actor: GateActor, sessionId: String): Session =
        owned(actor, sessionId).takeIf { it.endedAt != null } ?: throw SessionStillOpenException(sessionId)

    /**
     * The session a read is about: any member reads it, an agent token its own launch's only (answered as absent).
     *
     * @param actor The caller.
     * @param sessionId The session.
     *
     * @return The session.
     *
     * @throws RunSessionNotFoundException When it is absent or not readable by the caller.
     */
    suspend fun reading(actor: GateActor, sessionId: String): Session =
        sessions.find(actor.orgId, sessionId)?.takeIf { SessionAccess.canRead(actor, it) }
            ?: throw RunSessionNotFoundException()

    /**
     * The package a report names, which must be its session's: an agent never reports under another package.
     *
     * @param actor The caller.
     * @param digest The package's digest.
     * @param session The session reported for, or null outside one.
     *
     * @return The package.
     *
     * @throws RunPackageNotFoundException When the organization has none with that digest.
     * @throws RunPackageMismatchException When the session started with another package.
     */
    suspend fun pack(actor: GateActor, digest: String, session: Session?): RunPackage {
        val started = session?.packageDigest
        if (started != null && started != digest) throw RunPackageMismatchException()
        return packages.find(actor.orgId, digest) ?: throw RunPackageNotFoundException()
    }
}
