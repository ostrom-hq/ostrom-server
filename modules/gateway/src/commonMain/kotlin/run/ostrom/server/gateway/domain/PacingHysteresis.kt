// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * The candidate being stabilized: its pace and, for SLOW_DOWN, its level's interval (TeamCode's `PacingRecovery`).
 *
 * @property pace Its pace.
 * @property startedAt When it first appeared.
 * @property minInterval Its interval.
 */
data class PacingRecovery(val pace: Pace, val startedAt: Instant, val minInterval: Duration = Duration.ZERO)

/**
 * The stored advice a decision starts from (TeamCode's `PacingMemory`).
 *
 * @property advice The advice in force.
 * @property expiresAt When it expires.
 * @property recovery The looser candidate being stabilized, if any.
 */
data class PacingMemory(val advice: PacingAdvice, val expiresAt: Instant, val recovery: PacingRecovery?)

/**
 * What the controller stores (TeamCode's `PacingDecision`).
 *
 * @property advice The advice in force.
 * @property recovery The looser candidate being stabilized, if any.
 */
data class PacingDecision(val advice: PacingAdvice, val recovery: PacingRecovery? = null)

/**
 * The pending recovery is stored so that a replica restart cannot reset or skip stabilization (TeamCode's
 * `PacingHysteresisUseCase`). Tighter advice (see [PACING_STRICTNESS]) applies at once; looser advice must stay stable
 * for [recovery], including between SLOW_DOWN levels.
 *
 * @param recovery How long a looser advice stays stable before it applies.
 */
class PacingHysteresisUseCase(private val recovery: Duration = 2.minutes) {
    /**
     * The decision.
     *
     * @param previous The stored advice, or null.
     * @param candidate The advice of this pass.
     * @param now The time.
     */
    fun decide(previous: PacingMemory?, candidate: PacingAdvice, now: Instant): PacingDecision {
        val immediate = previous == null || previous.expiresAt <= now || previous.advice == candidate ||
            candidate.pace == Pace.UNKNOWN || PACING_STRICTNESS.compare(candidate, previous.advice) >= 0
        if (immediate) return PacingDecision(candidate)
        val stored = previous!!
        val pending = stored.recovery?.takeIf {
            it.pace == candidate.pace && it.minInterval == candidate.minInterval && it.startedAt <= now
        } ?: PacingRecovery(candidate.pace, now, candidate.minInterval)
        return if (now - pending.startedAt >= recovery) {
            PacingDecision(candidate)
        } else {
            PacingDecision(stored.advice, pending)
        }
    }
}
