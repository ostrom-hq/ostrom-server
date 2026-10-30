package dev.ostrom.server.sessions.domain.models

import dev.ostrom.server.gates.domain.models.GateActor

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

/** Who may read and write a session. */
object SessionAccess {
    /**
     * The person behind a caller: a device or agent token acts for its person.
     *
     * @param actor The caller.
     * @return The person's id.
     */
    fun person(actor: GateActor): String = actor.personId ?: actor.id

    /**
     * True when the caller may read the session: any member of its organization, but an agent token only reads its
     * own launch's sessions (audit A12).
     *
     * @param actor The caller.
     * @param session The session.
     * @return Whether it may read it.
     */
    fun canRead(actor: GateActor, session: Session): Boolean =
        actor.launchId == null || actor.launchId == session.launchId

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
