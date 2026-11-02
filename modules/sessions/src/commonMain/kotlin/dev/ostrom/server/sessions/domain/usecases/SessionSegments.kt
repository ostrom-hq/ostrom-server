// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sessions.domain.usecases

import dev.ostrom.server.sessions.domain.models.EndSession
import dev.ostrom.server.sessions.domain.models.InvalidSessionRequestException
import dev.ostrom.server.sessions.domain.models.NotYourSessionException
import dev.ostrom.server.sessions.domain.models.Opening
import dev.ostrom.server.sessions.domain.models.Session
import dev.ostrom.server.sessions.domain.models.StartSession
import dev.ostrom.server.sessions.domain.repositories.SessionsTransaction
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Opens the segments of an agent session (CORRECTIFS-2): the same launch finds its open segment again (`SessionStart`
 * comes back after a `/compact`); another launch of the same agent session closes the open segment ("superseded") and
 * opens the next one, `<id>~r<n>`, chained to it.
 *
 * @param close Closes a superseded segment, its receipt signed.
 * @param clock The time.
 */
class SessionSegments(private val close: CloseSessionUseCase, private val clock: Clock) {
    /**
     * Opens or finds the segment of a launch. Call it under [SessionsTransaction.lockRoot].
     *
     * @param tx The organization's transaction.
     * @param orgId The organization.
     * @param dev The person.
     * @param start What the launch says.
     * @param opening Why the segment opens, and whether the caller may replace another launch's live segment.
     * @return The open segment.
     */
    suspend fun open(
        tx: SessionsTransaction,
        orgId: Uuid,
        dev: String,
        start: StartSession,
        opening: Opening,
    ): Session {
        val last = tx.segments(start.id).lastOrNull()
        if (last != null && last.dev != dev) throw NotYourSessionException()
        val open = last?.takeIf { it.endedAt == null }
        if (open != null && sameLaunch(open.launchId, start.launchId) && !lateAdoption(open, start)) {
            return found(tx, open, start)
        }
        // S2-48: an agent token never closes a live launch's segment (nor its yes); a silent one, as the sweep would.
        if (open != null && !opening.mayReplace && !silent(open)) throw NotYourSessionException()
        val previous = if (open != null) close.closeLocked(tx, open, EndSession(source = Session.SUPERSEDED)) else last
        val source = opening.source
        val segment = (previous?.segment ?: 0) + 1
        val session = Session(
            orgId = orgId,
            id = if (segment == 1) start.id else "${start.id}~r$segment",
            rootId = start.id,
            segment = segment,
            resumedFrom = previous?.id,
            previousReceiptId = previous?.receiptId,
            launchId = start.launchId,
            repo = start.repo,
            realRepo = start.realRepo?.takeIf { it != start.repo },
            cwd = start.cwd,
            agent = start.agent,
            dev = dev,
            harnessVersion = start.harnessVersion,
            packageDigest = start.packageDigest,
            model = start.model,
            source = source,
            startedAt = now(),
            lastSeenAt = if (source != null) now() else null,
        )
        tx.insert(session)
        return session
    }

    private suspend fun found(tx: SessionsTransaction, last: Session, start: StartSession): Session {
        if (last.launchId != null || start.launchId == null) return last
        val named = last.copy(launchId = start.launchId, packageDigest = last.packageDigest ?: start.packageDigest)
        tx.update(named)
        return named
    }

    // Second audit L3: a launch names the segment its own hook opened a moment before, never an older unnamed one (a
    // session run without the launcher): another agent of the same person would take it over.
    private fun lateAdoption(open: Session, start: StartSession): Boolean =
        open.launchId == null && start.launchId != null && now() - open.startedAt > ADOPT_WITHIN

    private fun silent(session: Session): Boolean =
        now() - (session.lastSeenAt ?: session.startedAt) >= SweepStaleSessionsUseCaseImpl.STALE_AFTER

    private fun now(): Instant = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds())

    /** Checks and bounds what a launch sends. */
    companion object {
        private val SESSION_ID = Regex("[A-Za-z0-9_.:-]{1,100}")

        /**
         * Two launches are the same when either is unnamed (a hook without the launcher) or their ids match.
         *
         * @param a A launch.
         * @param b Another.
         * @return Whether they are the same launch.
         */
        fun sameLaunch(a: String?, b: String?): Boolean = a == null || b == null || a == b

        /**
         * Refuses a malformed start and trims what it sends to the stored sizes.
         *
         * @param start What the launch sent.
         * @return The start, bounded.
         */
        fun bounded(start: StartSession): StartSession {
            if (!SESSION_ID.matches(start.id)) throw InvalidSessionRequestException("Malformed session id.")
            if (start.agent.isBlank()) throw InvalidSessionRequestException("A session names its agent.")
            return start.copy(
                launchId = start.launchId?.clip(LAUNCH),
                // `?.let`, not `?.trim()?.clip`: a second null check nothing reaches, which Kover counts.
                repo = start.repo?.let { it.trim().clip(TEXT) },
                realRepo = start.realRepo?.let { it.trim().clip(TEXT) },
                cwd = start.cwd?.clip(PATH),
                agent = start.agent.trim().take(SHORT),
                harnessVersion = start.harnessVersion?.clip(SHORT),
                packageDigest = start.packageDigest?.clip(DIGEST),
                model = start.model?.clip(DIGEST),
            )
        }

        /**
         * Trims a text to a stored size; blank reads as absent.
         *
         * @param max The size.
         * @return The text, or null when blank.
         */
        fun String.clip(max: Int): String? = take(max).takeIf { it.isNotBlank() }

        /** How long after its hook opened a segment the launcher may still name it. */
        val ADOPT_WITHIN: Duration = 2.minutes

        /** Stored size of a launch id. */
        const val LAUNCH: Int = 64

        /** Stored size of a short name (agent, version). */
        const val SHORT: Int = 64

        /** Stored size of a digest or model. */
        const val DIGEST: Int = 128

        /** Stored size of a repository or source. */
        const val TEXT: Int = 300

        /** Stored size of a path. */
        const val PATH: Int = 1000
    }
}
