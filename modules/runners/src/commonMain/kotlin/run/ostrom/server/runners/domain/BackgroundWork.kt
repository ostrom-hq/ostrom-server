// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.domain

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * How long an organization lets the agent's background work hold off the idle pause (its cloud settings'
 * `backgroundWorkMaxMinutes`).
 */
fun interface BackgroundWorkLimit {
    /**
     * The cap.
     *
     * @param orgId The organization.
     *
     * @return Minutes from the start of an unbroken stretch of background work; 0 never holds the pause off.
     */
    suspend fun maxMinutes(orgId: Uuid): Int
}

/**
 * The agent's background work in the idle pause (TeamCode af1a5f7b): background subagents and commands, monitors and
 * scheduled wake-ups, which the runner reports on each heartbeat. Work still reported counts as activity now; work
 * that ended counts from when it was last seen; never later than the organization's cap after the start of its
 * unbroken stretch, so work that never ends (a stuck subagent, a `tail -f`, a week-long `/loop`) keeps a session
 * running for the cap plus its idle timeout at most.
 */
object BackgroundWork {
    /** The cap while the organization saved none. */
    const val DEFAULT_MAX_MINUTES = 240

    /**
     * [session] as a heartbeat at [now] writes it. A report right after a heartbeat that also reported it continues
     * the stretch (its start is kept); any other report starts a new one. A heartbeat without background work leaves
     * both fields, so the end of the work is known from the last report.
     *
     * @param session The session before the heartbeat.
     * @param background Whether the runner reports background work.
     * @param now The heartbeat's time.
     *
     * @return The session.
     */
    fun heartbeat(session: CloudSession, background: Boolean, now: Instant): CloudSession {
        if (!background) return session
        // The previous heartbeat reported it too: its time is the last report's.
        val continued = session.backgroundSeenAt != null && session.backgroundSeenAt == session.lastHeartbeatAt
        val since = session.backgroundSince?.takeIf { continued } ?: now
        return session.copy(backgroundSince = since, backgroundSeenAt = now)
    }

    /**
     * The activity time the agent's background work gives.
     *
     * @param session The session.
     * @param maxMinutes The organization's cap.
     *
     * @return The last report, at most [maxMinutes] after the start of its stretch; null without a report or with a
     *   cap of 0.
     */
    fun activity(session: CloudSession, maxMinutes: Int): Instant? {
        val seen = session.backgroundSeenAt
        val since = session.backgroundSince
        return if (seen == null || since == null || maxMinutes <= 0) null else minOf(seen, since + maxMinutes.minutes)
    }
}
