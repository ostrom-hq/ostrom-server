// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.workflowruns.domain.usecases

import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.sessions.domain.models.NotYourSessionException
import run.ostrom.server.sessions.domain.models.Session
import run.ostrom.server.sessions.domain.models.SessionAccess
import run.ostrom.server.sessions.domain.models.SessionClosedException
import run.ostrom.server.workflowruns.domain.models.RunPackage
import run.ostrom.server.workflowruns.domain.models.RunPackageMismatchException
import run.ostrom.server.workflowruns.domain.models.RunPackageNotFoundException
import run.ostrom.server.workflowruns.domain.models.RunSessionNotFoundException
import run.ostrom.server.workflowruns.domain.models.SessionStillOpenException
import run.ostrom.server.workflowruns.domain.repositories.RunAudience
import run.ostrom.server.workflowruns.domain.services.RunLaunches
import run.ostrom.server.workflowruns.domain.services.RunPackages
import run.ostrom.server.workflowruns.domain.services.RunSessions
import kotlin.uuid.Uuid

/**
 * Who may act on the runs of a session: the sessions module's rules (audit A12), read once here.
 *
 * @param sessions The sessions.
 * @param packages The packages.
 * @param launches The repository each launch names.
 */
class RunAccess(
    private val sessions: RunSessions,
    private val packages: RunPackages,
    private val launches: RunLaunches,
) {
    /**
     * The session a report is written for: only its person, an agent token of its own launch, and a service key of its
     * organization (its CI) write there.
     *
     * @param actor The caller.
     * @param sessionId The session, or null for a report outside a session (a person's only).
     *
     * @return The session, or null without one.
     *
     * @throws RunSessionNotFoundException When the organization has no such session.
     * @throws NotYourSessionException When it is another person's, or another
     *   launch's; or when an agent token or a service key names no session.
     * @throws SessionClosedException When the session has ended: its receipt is signed, nothing is added to it.
     */
    suspend fun writing(actor: GateActor, sessionId: String?): Session? {
        if (sessionId != null) {
            return owned(actor, sessionId).also { if (it.endedAt != null) throw SessionClosedException(it) }
        }
        // An agent token acts inside its launch's sessions only, and a service key inside its organization's: no
        // person stands behind a report outside a session.
        if (actor.kind == ActorKind.AGENT_SESSION || actor.kind == ActorKind.SERVICE) throw NotYourSessionException()
        SessionAccess.person(actor)
        return null
    }

    /**
     * A session only its person, an agent token of its own launch, and a service key of its organization write for.
     * The key is no person: the CI of the organization reports for any of its sessions, never another organization's
     * (the session is looked up in the key's organization only).
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
        if (actor.kind != ActorKind.SERVICE) SessionAccess.ensureOwner(actor, session)
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
     * The session a read is about: its person, an owner or an administrator reads it, and a person a gate of it was
     * sent to; an agent token its own launch's only; anyone else is answered as absent (TeamCode #238).
     *
     * @param actor The caller.
     * @param sessionId The session.
     *
     * @return The session.
     *
     * @throws RunSessionNotFoundException When it is absent or not readable by the caller.
     */
    suspend fun reading(actor: GateActor, sessionId: String): Session {
        val session = sessions.find(actor.orgId, sessionId) ?: throw RunSessionNotFoundException()
        if (SessionAccess.canRead(actor, session) || approves(actor, session.id)) return session
        throw RunSessionNotFoundException()
    }

    /**
     * The runs and notifications a list of the whole organization keeps for the caller (TeamCode #238): every one for
     * an owner or an administrator; a person's own and those of the sessions whose gates they approve otherwise.
     *
     * @param actor The caller: a person (an agent token lists its own session's only).
     *
     * @return Null for an owner or an administrator, the caller's audience otherwise.
     */
    suspend fun audience(actor: GateActor): RunAudience? {
        val person = actor.personId
        return when {
            SessionAccess.readsTeam(actor) -> null
            person == null -> RunAudience(null, emptyList())
            else -> RunAudience(person, sessions.approvedBy(actor.orgId, person))
        }
    }

    // Only a person approves: an agent token, a device or a service key never reads a session for its gates.
    private suspend fun approves(actor: GateActor, sessionId: String): Boolean {
        val person = actor.personId?.takeIf { actor.kind == ActorKind.USER } ?: return false
        return sessions.approves(actor.orgId, person, sessionId)
    }

    /**
     * The package a report names, which must be its session's: an agent never reports under another package, nor
     * under the package of another repository than its launch's (AUDIT-SECU-RUNNER-2 R3), nor under an older version
     * of its repository's package than the pinned one or the latest when the session started (R3b).
     *
     * @param actor The caller.
     * @param digest The package's digest.
     * @param session The session reported for, or null outside one.
     *
     * @return The package.
     *
     * @throws RunPackageNotFoundException When the organization has none with that digest.
     * @throws RunPackageMismatchException When the session started with another package, or the package is not its
     *   repository's, or an older version of it.
     */
    suspend fun pack(actor: GateActor, digest: String, session: Session?): RunPackage {
        val started = session?.packageDigest
        if (started != null && started != digest) throw RunPackageMismatchException()
        val pack = packages.find(actor.orgId, digest) ?: throw RunPackageNotFoundException()
        if (session != null) requireServed(actor.orgId, session, pack)
        return pack
    }

    // The repository the launch names wins over the one the session declares: the hooks write the session's, the
    // launch's comes from the CLI's own call or the cloud session. A session that names none binds no package. Within
    // the repository, the package is the pinned one or the latest when the session started.
    private suspend fun requireServed(orgId: Uuid, session: Session, pack: RunPackage) {
        val expected = (session.launchId?.let { launches.repo(orgId, it) } ?: session.repo)?.takeIf { it.isNotEmpty() }
        val repo = pack.envelope.repo
        if (expected != null && (repo == null || !RepoNames.same(expected, repo))) throw RunPackageMismatchException()
        if (!packages.current(orgId, pack, session.startedAt)) throw RunPackageMismatchException()
    }
}
