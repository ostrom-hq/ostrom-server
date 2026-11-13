// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The scheduling class of a call in the admission queue (`x-ostrom-workload`): a hint the caller sets, never a
 * permission. It only orders the waiting calls.
 *
 * @property wire Its name in the header.
 */
enum class Workload(val wire: String) {
    /** A person waits for the answer: the default. */
    INTERACTIVE("interactive"),

    /** Unattended work (`ostrom run --workload background`): it waits behind the interactive calls for a while. */
    BACKGROUND("background"),
    ;

    /** Reading the header. */
    companion object {
        /** The class the header names: interactive without one, null for any other value (or the header twice). */
        fun named(value: String?): Workload? =
            if (value == null) INTERACTIVE else entries.firstOrNull { it.wire == value }
    }
}

/**
 * A model call asking to start, as the admission queue sees it: never its body, its prompt or its credential.
 *
 * @property id Its place in the queue, the same over all its waits.
 * @property orgId The org.
 * @property memberId The member.
 * @property scope What it competes for with the other waiting calls: the shared accounts of one provider (`claude`,
 *   `codex`), or `key` for a call no shared account serves.
 * @property workload Its class.
 * @property limit The most calls the member runs at once (`memberConcurrency`), or null for no limit.
 * @property limitedMembers The org's members who have a limit, by id: the waiting calls take turns only while one of
 *   theirs waits (TeamCode #87).
 */
data class AdmissionRequest(
    val id: Uuid,
    val orgId: Uuid,
    val memberId: Uuid,
    val scope: String,
    val workload: Workload,
    val limit: Int?,
    val limitedMembers: Set<String> = emptySet(),
)

/** What the queue answered a call. */
sealed interface AdmissionEntry {
    /** It runs now: it counts against its member's limit until it is released. */
    data object Admitted : AdmissionEntry

    /**
     * It waits.
     *
     * @property blocked By its own member's limit (it does not compete for the next turn until it can run), rather than
     *   by another call that goes before it.
     */
    data class Waiting(val blocked: Boolean) : AdmissionEntry
}

/**
 * A waiting call, as the next turn is decided (TeamCode's `QueueCandidate`).
 *
 * @property id The call.
 * @property memberId Its member.
 * @property workload Its class.
 * @property waited How long it has waited.
 * @property lastTurn Its member's last turn in its scope: 0 for a member never served there.
 */
data class QueueCandidate(
    val id: Uuid,
    val memberId: Uuid,
    val workload: Workload,
    val waited: Duration,
    val lastTurn: Long,
)

/**
 * The order of the queue, as TeamCode orders it: interactive calls first, a background call joining them once it has
 * waited [PROMOTION]; then the member served longest ago (members take turns, so one member's many calls never starve
 * another's); then the call that waited longest (one member's calls go in arrival order). Turns are counted by
 * calls, not by tokens or time.
 */
object QueueTurns {
    /** How long a background call waits behind the interactive ones at most. */
    val PROMOTION: Duration = 30.seconds

    private val ORDER = compareBy<QueueCandidate> {
        if (it.workload == Workload.INTERACTIVE || it.waited >= PROMOTION) 0 else 1
    }.thenBy { it.lastTurn }.thenByDescending { it.waited }.thenBy { it.id.toString() }

    /**
     * Whether [call] runs now: its member is under [limit] with [running] calls, and no call of [others] goes before
     * it. Turns order the members who wait behind a limit: while no waiting call's member has one, every call runs as
     * it asks (a free account takes them all in one round, where taking turns would let one through per poll and time
     * the others out, TeamCode #87).
     *
     * @param call The call asking.
     * @param others The other calls of its scope that wait and may run.
     * @param running The calls its member runs now.
     * @param limit Its member's limit, or null.
     * @param limited The org's members who have a limit, by id.
     */
    fun decide(
        call: QueueCandidate,
        others: List<QueueCandidate>,
        running: Int,
        limit: Int?,
        limited: Set<String>,
    ): AdmissionEntry = when {
        limit != null && running >= limit -> AdmissionEntry.Waiting(blocked = true)
        limit == null && others.none { it.memberId.toString() in limited } -> AdmissionEntry.Admitted
        (others + call).minWith(ORDER).id != call.id -> AdmissionEntry.Waiting(blocked = false)
        else -> AdmissionEntry.Admitted
    }
}

/**
 * The waiting calls of an org, for a member.
 *
 * @property waiting The org's calls waiting now.
 * @property waitingMine The member's among them.
 */
data class QueueCounts(val waiting: Int, val waitingMine: Int)

/**
 * The admission queue, shared by every replica (TeamCode's `admission_waiters` and `admission_turns`). Only metadata:
 * bodies stay with their HTTP request. Times are the database's clock.
 */
interface AdmissionQueueRepository {
    /**
     * In one transaction under the org's lock: drops the org's expired entries, then admits [request] when
     * [QueueTurns.decide] says so (its row then counts against the member's limit for [RUNNING_LEASE], and its
     * member's turn in its scope is taken), or keeps it waiting: its row is written when [first], renewed otherwise
     * (for [WAITING_LEASE]).
     *
     * @throws QueueRefusedException [RefusalKind.QUEUE_FULL] when [first] and the org has [ORG_BOUND] calls waiting,
     *   or a member with a limit [MEMBER_BOUND]; [RefusalKind.QUEUE_EXPIRED] when not [first] and its row is gone.
     */
    suspend fun enter(request: AdmissionRequest, first: Boolean): AdmissionEntry

    /**
     * Puts a running call back to wait, at its place (its arrival time kept, and the turn it took given back to its
     * member): the accounts it needs are busy.
     */
    suspend fun requeue(orgId: Uuid, id: Uuid)

    /** Extends a running call's lease by [RUNNING_LEASE] from now; nothing when it is gone. */
    suspend fun renew(orgId: Uuid, id: Uuid)

    /** Removes the call: it ended, or stopped waiting. Nothing when it is gone. */
    suspend fun release(orgId: Uuid, id: Uuid)

    /** The calls waiting now, the org's and [memberId]'s. */
    suspend fun counts(orgId: Uuid, memberId: Uuid): QueueCounts

    /** The bounds and leases (TeamCode's). */
    companion object {
        /** Calls waiting at most per org. */
        const val ORG_BOUND: Int = 128

        /** Calls waiting at most per member, for a member with a concurrency limit only. */
        const val MEMBER_BOUND: Int = 8

        /** A waiting call not renewed for this long is gone (its replica stopped). */
        val WAITING_LEASE: Duration = 15.seconds

        /** A waiting call competes for the next turn only while it polled within this. */
        val ALIVE: Duration = 5.seconds

        /** A running call never released (its replica died) frees its member's place after this. */
        val RUNNING_LEASE: Duration = 15.minutes

        /** How often a running call renews its lease. */
        val RUNNING_RENEWAL: Duration = 5.minutes
    }
}

/**
 * A call the queue refuses.
 *
 * @property kind [RefusalKind.QUEUE_FULL] or [RefusalKind.QUEUE_EXPIRED].
 */
class QueueRefusedException(val kind: RefusalKind) : Exception("The admission queue refused the call: $kind.")
