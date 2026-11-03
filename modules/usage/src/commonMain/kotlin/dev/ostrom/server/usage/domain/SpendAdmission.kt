// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage.domain

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Whose limit: the whole org's, or one member's. */
enum class SpendScope { ORG, MEMBER }

/**
 * What a model call may cost at most, held against the limits until its cost is in the ledger.
 *
 * @property orgId The org.
 * @property memberId The member.
 * @property callId The call: the id of its ledger event, which settles the hold ([UsageRecorder.settle]).
 * @property micros USD millionths.
 */
data class SpendHold(val orgId: Uuid, val memberId: Uuid, val callId: Uuid, val micros: Long)

/**
 * What is spent and held when a call asks to spend, other calls only (a call asking again does not count its own hold).
 *
 * @property orgSpent The org's spend this month.
 * @property orgHeld What the org's calls in flight hold.
 * @property memberSpent The member's spend this month.
 * @property memberHeld What the member's calls in flight hold.
 * @property orgDaySpent The org's spend today (UTC), read only for a daily limit.
 * @property memberDaySpent The member's spend today (UTC), read only for a daily limit.
 */
data class SpendTotals(
    val orgSpent: Long = 0,
    val orgHeld: Long = 0,
    val memberSpent: Long = 0,
    val memberHeld: Long = 0,
    val orgDaySpent: Long = 0,
    val memberDaySpent: Long = 0,
)

/** The answer to a call asking to spend. */
sealed interface Admission {
    /** It may go: its hold is written. */
    data object Admitted : Admission

    /**
     * A limit is reached: nothing more this month, or today for a daily limit.
     *
     * @property scope Which.
     * @property limitMicros Its value.
     * @property daily Whether it is a daily limit.
     */
    data class Exhausted(val scope: SpendScope, val limitMicros: Long, val daily: Boolean = false) : Admission

    /**
     * What is left of a limit, once the calls in flight are counted, does not cover this call.
     *
     * @property scope Which.
     * @property limitMicros Its value.
     * @property availableMicros What is left of it, never below 0.
     * @property daily Whether it is a daily limit.
     */
    data class Insufficient(
        val scope: SpendScope,
        val limitMicros: Long,
        val availableMicros: Long,
        val daily: Boolean = false,
    ) : Admission
}

/**
 * The daily limits that apply to a call, counted from the start of the UTC day; at least one is set.
 *
 * @property since The start of the day.
 * @property orgMicros The org's.
 * @property memberMicros The member's.
 */
data class DailySpendLimits(val since: Instant, val orgMicros: Long?, val memberMicros: Long?)

/**
 * The limits that apply to a call; null means none.
 *
 * @property orgMicros The org's monthly limit.
 * @property memberMicros The member's monthly limit.
 * @property daily The daily limits, null when there is none.
 */
data class SpendLimits(val orgMicros: Long?, val memberMicros: Long?, val daily: DailySpendLimits? = null) {
    /** Whether a limit applies at all. */
    val any: Boolean get() = orgMicros != null || memberMicros != null || daily != null

    /**
     * The rule: a limit is reached when what is spent meets it; a call goes only when what is spent, what the other
     * calls in flight hold and its own reserve stay within every limit. So the calls admitted never hold more than a
     * limit leaves, however many ask at once. A reached limit answers first, the org's before the member's, the
     * month's before the day's; the calls in flight count against the day as against the month.
     *
     * @param totals What is spent and held.
     * @param reserveMicros What the call may cost.
     *
     * @return The answer.
     */
    fun admit(totals: SpendTotals, reserveMicros: Long): Admission {
        val orgDay = daily?.orgMicros
        val memberDay = daily?.memberMicros
        return reached(SpendScope.ORG, orgMicros, totals.orgSpent, false)
            ?: reached(SpendScope.ORG, orgDay, totals.orgDaySpent, true)
            ?: reached(SpendScope.MEMBER, memberMicros, totals.memberSpent, false)
            ?: reached(SpendScope.MEMBER, memberDay, totals.memberDaySpent, true)
            ?: short(SpendScope.ORG, orgMicros, totals.orgSpent + totals.orgHeld, reserveMicros, false)
            ?: short(SpendScope.ORG, orgDay, totals.orgDaySpent + totals.orgHeld, reserveMicros, true)
            ?: short(SpendScope.MEMBER, memberMicros, totals.memberSpent + totals.memberHeld, reserveMicros, false)
            ?: short(SpendScope.MEMBER, memberDay, totals.memberDaySpent + totals.memberHeld, reserveMicros, true)
            ?: Admission.Admitted
    }

    private fun reached(scope: SpendScope, limit: Long?, spent: Long, daily: Boolean): Admission? =
        limit?.takeIf { spent >= it }?.let { Admission.Exhausted(scope, it, daily) }

    private fun short(scope: SpendScope, limit: Long?, committed: Long, reserve: Long, daily: Boolean): Admission? =
        limit?.takeIf { committed + reserve > it }
            ?.let { Admission.Insufficient(scope, it, (it - committed).coerceAtLeast(0), daily) }
}

/** Admits a model call against the limits, on the ledger and the holds. Bound in Koin by the usage module. */
interface SpendAdmission {
    /**
     * Admits the call and writes its hold, or refuses it; calls of one org are admitted one at a time.
     *
     * @param hold The call and its reserve.
     * @param limits The limits that apply.
     * @param since The start of the month the monthly limits count (the daily ones carry their day).
     *
     * @return The answer.
     */
    suspend fun admit(hold: SpendHold, limits: SpendLimits, since: Instant): Admission
}

/**
 * [SpendAdmission] on the ledger. Without a limit, nothing is held and nothing is locked.
 *
 * @param repository The ledger and the holds.
 * @param clock The time.
 * @param holdFor How long a hold counts when its call is never recorded (a replica lost mid-call): longer than any
 *   stream.
 */
class SpendAdmissionImpl(
    private val repository: UsageRepository,
    private val clock: Clock,
    private val holdFor: Duration = 1.hours,
) : SpendAdmission {
    override suspend fun admit(hold: SpendHold, limits: SpendLimits, since: Instant): Admission {
        if (!limits.any) return Admission.Admitted
        val now = clock.now()
        return repository.holdWithin(hold, limits, HoldWindow(since, now, now + holdFor))
    }
}

/**
 * The times of an admission.
 *
 * @property since The start of the month the limits count.
 * @property now Holds that expire by then no longer count.
 * @property expiresAt When the new hold expires.
 */
data class HoldWindow(val since: Instant, val now: Instant, val expiresAt: Instant)
