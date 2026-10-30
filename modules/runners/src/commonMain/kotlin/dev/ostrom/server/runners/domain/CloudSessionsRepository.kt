package dev.ostrom.server.runners.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Storage of cloud sessions and of their organizations' monthly usage. */
interface CloudSessionsRepository {
    /**
     * Creates a session unless its organization already has [maxRunning] sessions holding an execution. The check
     * and the insert are serialized per organization, so two concurrent creations never both pass the cap.
     *
     * @param session The session to create.
     * @param maxRunning The organization's cap.
     *
     * @return True when the session was created, false when the cap was reached.
     */
    suspend fun createWithinCap(session: CloudSession, maxRunning: Int): Boolean

    /**
     * Reads a session of an organization.
     *
     * @param orgId The organization.
     * @param id The session.
     *
     * @return The session, or null when it does not exist in this organization.
     */
    suspend fun get(orgId: Uuid, id: Uuid): CloudSession?

    /**
     * Reads a session by id, whatever its organization: for the reconciler and the runner channel only.
     *
     * @param id The session.
     *
     * @return The session, or null.
     */
    suspend fun getById(id: Uuid): CloudSession?

    /**
     * Lists the sessions of a member, newest first, by keyset.
     *
     * @param orgId The organization.
     * @param ownerId The owner.
     * @param before Only sessions created before this one (a cursor from the previous page), or null.
     * @param limit The page size.
     *
     * @return The page.
     */
    suspend fun list(orgId: Uuid, ownerId: String, before: Uuid?, limit: Int): List<CloudSession>

    /**
     * Sets the desired state and asks for a reconciliation now. A final session is left as it is.
     *
     * @param orgId The organization.
     * @param id The session.
     * @param desiredState The new desired state.
     * @param now The time, to schedule the reconciliation.
     *
     * @return The session after the change, or null when it does not exist in this organization.
     */
    suspend fun setDesiredState(orgId: Uuid, id: Uuid, desiredState: DesiredState, now: Instant): CloudSession?

    /**
     * Sets the desired state to running unless the organization already has [maxRunning] sessions holding an
     * execution, serialized per organization like [createWithinCap]. A session that already holds one is not
     * counted twice.
     *
     * @param orgId The organization.
     * @param id The session.
     * @param maxRunning The organization's cap.
     * @param now The time.
     *
     * @return The outcome.
     */
    suspend fun resumeWithinCap(orgId: Uuid, id: Uuid, maxRunning: Int, now: Instant): ResumeOutcome

    /**
     * Writes a session if nobody changed it since it was read (compare-and-set on [CloudSession.generation]); the
     * stored generation becomes `session.generation + 1`.
     *
     * @param session The session as it must be stored, with the generation it was read with.
     * @param nextReconcileAt When the reconciler must look at it again.
     *
     * @return The stored session, or null when another writer changed it first.
     */
    suspend fun compareAndSet(session: CloudSession, nextReconcileAt: Instant?): CloudSession?

    /**
     * Moves the last activity forward (never backward), without a compare-and-set: terminal input and requests to
     * the model are frequent and must never conflict with the reconciler.
     *
     * @param id The session.
     * @param at The time of the activity.
     */
    suspend fun touchActivity(id: Uuid, at: Instant)

    /**
     * Finds the session whose current execution holds a runner token.
     *
     * @param tokenHash The SHA-256 (hex) of the token.
     *
     * @return The session, or null.
     */
    suspend fun findByRunnerTokenHash(tokenHash: String): CloudSession?

    /**
     * Claims sessions due for reconciliation with a lease, so that one replica works on a session at a time.
     *
     * @param owner The claiming replica.
     * @param now The time.
     * @param leaseUntil Until when the claim holds.
     * @param limit How many sessions to claim at most.
     *
     * @return The claimed sessions.
     */
    suspend fun claimDue(owner: String, now: Instant, leaseUntil: Instant, limit: Int): List<CloudSession>

    /**
     * Releases a claim if it is still held.
     *
     * @param id The session.
     * @param owner The replica that claimed it.
     * @param nextReconcileAt When to look at it again; null for never (a final session).
     */
    suspend fun release(id: Uuid, owner: String, nextReconcileAt: Instant?)

    /**
     * Reads the execution seconds an organization used in a month.
     *
     * @param orgId The organization.
     * @param month The month, `YYYY-MM` (UTC).
     *
     * @return The seconds.
     */
    suspend fun usageSeconds(orgId: Uuid, month: String): Long

    /**
     * Adds execution seconds to an organization's month.
     *
     * @param orgId The organization.
     * @param month The month, `YYYY-MM` (UTC).
     * @param seconds The seconds to add.
     */
    suspend fun addUsage(orgId: Uuid, month: String, seconds: Long)
}

/** What [CloudSessionsRepository.resumeWithinCap] did. */
sealed interface ResumeOutcome {
    /**
     * The desired state is now running.
     *
     * @property session The session after the change.
     */
    data class Resumed(val session: CloudSession) : ResumeOutcome

    /** The organization is at its cap. */
    data object AtCap : ResumeOutcome

    /** No such session in the organization. */
    data object NotFound : ResumeOutcome
}
