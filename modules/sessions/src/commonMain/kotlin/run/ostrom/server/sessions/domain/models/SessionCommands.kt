// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.sessions.domain.models

import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.kernel.auth.OrgRole

/**
 * What the hook `SessionStart` (or the launcher) sends.
 *
 * @property id The agent's session id.
 * @property launchId The launch, when the body names it; an agent token's own launch always wins.
 * @property repo The repository.
 * @property realRepo The repository it really runs in.
 * @property cwd The working directory.
 * @property agent The coding agent.
 * @property harnessVersion The launcher or plugin version.
 * @property packageDigest The session package.
 * @property model The model.
 */
data class StartSession(
    val id: String,
    val launchId: String? = null,
    val repo: String? = null,
    val realRepo: String? = null,
    val cwd: String? = null,
    val agent: String,
    val harnessVersion: String? = null,
    val packageDigest: String? = null,
    val model: String? = null,
)

/**
 * What the hook `SessionEnd` (or the launcher) sends.
 *
 * @property tools How many tool calls.
 * @property model The model.
 * @property costUsd What it cost.
 * @property source Why it ended.
 * @property exitCode The launcher's exit code.
 */
data class EndSession(
    val tools: Int? = null,
    val model: String? = null,
    val costUsd: Double? = null,
    val source: String? = null,
    val exitCode: Int? = null,
)

/**
 * A reopened segment.
 *
 * @property session The open segment.
 * @property reopened False when the segment was already open (the call is idempotent).
 */
data class Reopened(val session: Session, val reopened: Boolean)

/**
 * How a segment opens.
 *
 * @property source Why it opens (`reopened: …`), or null for a plain start.
 * @property mayReplace Whether the caller may close another launch's live open segment: a person may, an agent token
 *   may not (S2-48).
 */
data class Opening(val source: String?, val mayReplace: Boolean) {
    /** The openings. */
    companion object {
        /**
         * @param actor The caller.
         * @param source Why it opens.
         * @return The opening of [actor].
         */
        fun of(actor: GateActor, source: String?): Opening = Opening(source, actor.kind != ActorKind.AGENT_SESSION)
    }
}

/** Who may read and write a session. */
object SessionAccess {
    /**
     * The person behind a caller: a device or agent token acts for its person, and a credential without one acts for
     * nobody (S2-48, never its own id).
     *
     * @param actor The caller.
     * @return The person's id.
     */
    fun person(actor: GateActor): String = actor.personId ?: throw NotYourSessionException()

    /** The roles that read every session of their organization, as TeamCode's run records (#238). */
    val TEAM_READERS: Set<OrgRole> = setOf(OrgRole.OWNER, OrgRole.ADMIN)

    /**
     * True when the caller may read the session: its person, and an owner or an administrator of its organization
     * (TeamCode #238: a session is its member's; only the team's administrators read the others'); an agent token
     * only reads its own launch's sessions (audit A12), and none without a launch (S2-48).
     *
     * @param actor The caller.
     * @param session The session.
     * @return Whether it may read it.
     */
    fun canRead(actor: GateActor, session: Session): Boolean =
        if (actor.kind == ActorKind.AGENT_SESSION) {
            actor.launchId == session.launchId
        } else {
            readsTeam(actor) || actor.personId == session.dev
        }

    /**
     * True when the caller reads the sessions of the whole organization: an owner or an administrator (an agent token
     * carries no role).
     *
     * @param actor The caller.
     * @return Whether it does.
     */
    fun readsTeam(actor: GateActor): Boolean = actor.role in TEAM_READERS

    /**
     * Refuses a write by anyone but the session's person, or by an agent token of another launch.
     *
     * @param actor The caller.
     * @param session The session.
     */
    fun ensureOwner(actor: GateActor, session: Session) {
        if (person(actor) != session.dev || !canRead(actor, session)) throw NotYourSessionException()
    }
}
