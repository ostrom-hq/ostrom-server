// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import run.ostrom.server.kernel.audit.OrgAuditRecord
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Storage of cloud sessions and of their organizations' monthly usage. */
// One port per table: splitting it would only spread the same SQL over several classes.
@Suppress("TooManyFunctions")
interface CloudSessionsRepository {
    /**
     * Creates a session unless its organization already has [maxRunning] sessions holding an execution. The check
     * and the insert are serialized per organization, so two concurrent creations never both pass the cap.
     *
     * @param session The session to create.
     * @param maxRunning The organization's cap.
     * @param journal The audit entry of the change (`runners.cloud.onbehalf.writes.v1`, TeamCode's `CloudWrite`),
     *   written in its transaction: kept only when the session is created, and the creation does not
     *   happen when it cannot be written. Null for a change of the server itself, which journals nothing.
     *
     * @return True when the session was created, false when the cap was reached.
     */
    suspend fun createWithinCap(session: CloudSession, maxRunning: Int, journal: OrgAuditRecord? = null): Boolean

    /**
     * Creates a session under an `Idempotency-Key` (TeamCode's `SqlCloudSessionStore.insert`), in one transaction
     * serialized per organization like [createWithinCap]: a live record of the key from the same member answers the
     * session it created when the request is the same, and refuses another request; an expired one is replaced. The
     * record is written with the session.
     *
     * @param session The session to create.
     * @param maxRunning The organization's cap.
     * @param key The key, the request's digest and when the record expires.
     * @param journal The audit entry of the change (`runners.cloud.onbehalf.writes.v1`, TeamCode's `CloudWrite`),
     *   written in its transaction: kept only when the session is created (not on a replay), and the creation does not
     *   happen when it cannot be written. Null for a change of the server itself, which journals nothing.
     *
     * @return What happened.
     */
    suspend fun createKeyed(
        session: CloudSession,
        maxRunning: Int,
        key: CreateKey,
        journal: OrgAuditRecord? = null,
    ): KeyedCreateOutcome

    /**
     * Deletes a bounded batch of the expired idempotency records of every organization (TeamCode's `maintain`).
     *
     * @return How many were deleted.
     */
    suspend fun purgeExpiredCreateKeys(): Long

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
     * @param kinds The kinds listed (`?kind=`).
     *
     * @return The page.
     */
    suspend fun list(
        orgId: Uuid,
        ownerId: String,
        before: Uuid?,
        limit: Int,
        kinds: Set<SessionKind> = setOf(SessionKind.CLOUD),
    ): List<CloudSession>

    /**
     * Lists the sessions of a member that hold an execution (creating, running, pausing, resuming): the runners at
     * work for them, newest first.
     *
     * @param orgId The organization.
     * @param ownerId The owner.
     * @param limit The most to return.
     *
     * @return The sessions.
     */
    suspend fun executions(orgId: Uuid, ownerId: String, limit: Int): List<CloudSession>

    /**
     * Sets the desired state and asks for a reconciliation now. A final session is left as it is.
     *
     * @param orgId The organization.
     * @param id The session.
     * @param desiredState The new desired state.
     * @param now The time, to schedule the reconciliation.
     * @param journal The audit entry of the change (`runners.cloud.onbehalf.writes.v1`, TeamCode's `CloudWrite`),
     *   written in its transaction: kept only when the session is changed, and the change does not
     *   happen when it cannot be written. Null for a change of the server itself, which journals nothing.
     *
     * @return The session after the change, or null when it does not exist in this organization.
     */
    suspend fun setDesiredState(
        orgId: Uuid,
        id: Uuid,
        desiredState: DesiredState,
        now: Instant,
        journal: OrgAuditRecord? = null,
    ): CloudSession?

    /**
     * Sets whose GitHub access a session uses.
     *
     * @param orgId The organization.
     * @param id The session.
     * @param access The mode.
     * @param now The time.
     *
     * @return The session after the change, or null when it does not exist, is over, or is not paused for `none`.
     */
    suspend fun setGitHubAccess(orgId: Uuid, id: Uuid, access: GitHubAccessMode, now: Instant): CloudSession?

    /**
     * Sets the desired state to running unless the organization already has [maxRunning] sessions holding an
     * execution, serialized per organization like [createWithinCap]. A session that already holds one is not
     * counted twice. A session being deleted, or over, is left as it is: [ResumeOutcome.NotFound].
     *
     * @param orgId The organization.
     * @param id The session.
     * @param maxRunning The organization's cap.
     * @param now The time.
     * @param journal The audit entry of the change (`runners.cloud.onbehalf.writes.v1`, TeamCode's `CloudWrite`),
     *   written in its transaction: kept only when the session is resumed, and the resume does not
     *   happen when it cannot be written. Null for a change of the server itself, which journals nothing.
     *
     * @return The outcome.
     */
    suspend fun resumeWithinCap(
        orgId: Uuid,
        id: Uuid,
        maxRunning: Int,
        now: Instant,
        journal: OrgAuditRecord? = null,
    ): ResumeOutcome

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
     * The sessions a device hosts or hosted, every organization's (a device is a person's, not an organization's).
     *
     * @param deviceId The device.
     * @param live Only the ones not over (neither terminated nor failed).
     *
     * @return Their ids and organizations.
     */
    suspend fun hostedOn(deviceId: Uuid, live: Boolean = false): List<SessionRef>

    /**
     * The local session of a launch (TeamCode's `findLocal`): one per launch in an organization, whatever its state
     * (a unique index holds it).
     *
     * @param orgId The organization.
     * @param launchId The `ostrom <agent>` launch.
     *
     * @return The session, or null.
     */
    suspend fun findLocal(orgId: Uuid, launchId: String): CloudSession?

    /**
     * A device daemon's queue (TeamCode's `DeviceDaemonStore.orders`): the organization's sessions started through the
     * device's daemon (a folder) that wait for their host and should run, oldest request first.
     *
     * @param orgId The organization the daemon polls in.
     * @param deviceId The device.
     * @param limit The most sessions.
     *
     * @return The sessions.
     */
    suspend fun daemonOrders(orgId: Uuid, deviceId: Uuid, limit: Int): List<CloudSession>

    /**
     * Ends a session on a removed device: asked to terminate with [RemoteSessionRules.DEVICE_REMOVED] unless it is over
     * or ending already, and its host forgotten either way, in one transaction of its organization.
     *
     * @param ref The session.
     * @param deviceId The device removed.
     * @param now When.
     *
     * @return True when it was asked to terminate.
     */
    suspend fun endOnRemovedDevice(ref: SessionRef, deviceId: Uuid, now: Instant): Boolean

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
     * The sessions of every organization created before cloud profiles, still without one, but the terminated ones:
     * read across organizations through `runners_sessions_without_profile` (owned by `ostrom_router`), each session
     * then read and written in its own.
     *
     * @param after The last session of the page before, or null for the first page.
     * @param limit How many at most.
     *
     * @return Their ids and organizations, by id: exactly [limit] unless it is the last page.
     */
    suspend fun withoutProfile(after: Uuid?, limit: Int): List<SessionRef>

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

/**
 * A session as a cross-organization read names it.
 *
 * @property orgId Its organization.
 * @property id The session.
 */
data class SessionRef(val orgId: Uuid, val id: Uuid)

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

    /** No such session in the organization, or it is being deleted or over. */
    data object NotFound : ResumeOutcome
}

/**
 * The `Idempotency-Key` of a creation, as stored with the session it creates.
 *
 * @property key The key, scoped to the organization and the initiating member.
 * @property requestDigest The SHA-256 (hex) of the canonical request.
 * @property expiresAt When the record stops replaying, 24 hours after the creation.
 * @property memberId The initiating member it is kept for; the session's owner when null.
 */
data class CreateKey(
    val key: String,
    val requestDigest: String,
    val expiresAt: Instant,
    val memberId: String? = null,
)

/** What [CloudSessionsRepository.createKeyed] did. */
sealed interface KeyedCreateOutcome {
    /** The session was created with the key's record. */
    data object Created : KeyedCreateOutcome

    /**
     * The key already created a session from the same request: nothing was created.
     *
     * @property session That session, as it is now.
     */
    data class Replayed(val session: CloudSession) : KeyedCreateOutcome

    /** The key was used with another request, or its session no longer exists. */
    data object Conflict : KeyedCreateOutcome

    /** The organization is at its cap. */
    data object AtCap : KeyedCreateOutcome
}
