// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sessions.domain.usecases

import run.ostrom.server.kernel.observability.OstromMetrics
import run.ostrom.server.kernel.sessions.LaunchLabels
import run.ostrom.server.sessions.domain.models.EndSession
import run.ostrom.server.sessions.domain.models.InvalidSessionRequestException
import run.ostrom.server.sessions.domain.models.NotYourSessionException
import run.ostrom.server.sessions.domain.models.Opening
import run.ostrom.server.sessions.domain.models.Session
import run.ostrom.server.sessions.domain.models.SessionResumedElsewhereException
import run.ostrom.server.sessions.domain.models.StartSession
import run.ostrom.server.sessions.domain.repositories.SessionsTransaction
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
 * @param metrics Counts the sessions that start (a first segment), `where = local`: written by a launcher.
 * @param labels The labels of a launch, copied onto each segment it opens (`sessions.labels.v1`); null copies none.
 */
class SessionSegments(
    private val close: CloseSessionUseCase,
    private val clock: Clock,
    private val metrics: OstromMetrics,
    private val labels: LaunchLabels? = null,
) {
    /**
     * Opens or finds the segment of a launch. Call it under [SessionsTransaction.lockRoot], in a transaction that
     * [replacing] runs.
     *
     * @param tx The organization's transaction.
     * @param orgId The organization.
     * @param dev The person.
     * @param start What the launch says.
     * @param opening Why the segment opens, and whether the caller may replace another launch's live segment.
     * @param labels The launch's labels ([labelsOf], read before the transaction: a read in it would take a second
     *   connection of the pool).
     * @return The open segment.
     */
    @Suppress("LongParameterList") // The transaction, the caller, what starts and why, read before the transaction.
    suspend fun open(
        tx: SessionsTransaction,
        orgId: Uuid,
        dev: String,
        start: StartSession,
        opening: Opening,
        labels: Map<String, String>,
    ): Session {
        val last = tx.segments(start.id).lastOrNull()
        if (last != null && last.dev != dev) throw NotYourSessionException()
        val open = last?.takeIf { it.endedAt == null }
        if (open != null && sameLaunch(open.launchId, start.launchId) && !lateAdoption(open, start)) {
            return found(tx, open, start, labels)
        }
        if (open != null) supersede(open, opening)
        // An end that failed before its receipt (a superseded segment's, or its launcher's): signed first, so that the
        // next segment cites it.
        if (last != null && last.receiptId == null) throw SupersededSegmentException(last)
        val source = opening.source
        val segment = (last?.segment ?: 0) + 1
        val session = Session(
            orgId = orgId,
            id = if (segment == 1) start.id else "${start.id}~r$segment",
            rootId = start.id,
            segment = segment,
            resumedFrom = last?.id,
            previousReceiptId = last?.receiptId,
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
            labels = labels,
        )
        tx.insert(session)
        // Counted once written: only a commit that fails after it would leave one too many.
        if (segment == 1) metrics.sessionStarted(OstromMetrics.agentOf(start.agent), "local")
        return session
    }

    /**
     * Runs [attempt], a transaction that calls [open] under the root's lock, until it opens or finds a segment. When
     * [open] supersedes the live segment of another launch (or finds the last one ended without its receipt), that
     * transaction ends without writing, the segment is ended with its receipt signed ([CloseSessionUseCase], never
     * inside a transaction: CRL-03), and [attempt] runs again on what is written then, so the new segment chains to
     * the ended one and its receipt.
     *
     * @param orgId The organization.
     * @param attempt The caller's transaction.
     * @return What [attempt] returns.
     * @throws SessionResumedElsewhereException When it still supersedes a segment after [MAX_SUPERSEDED] ends.
     */
    suspend fun <T> replacing(orgId: Uuid, attempt: suspend () -> T): T {
        var ended = 0
        while (true) {
            val live = try {
                return attempt()
            } catch (e: SupersededSegmentException) {
                e.live
            }
            // Other launches keep taking the session over between the ends: a conflict, as a reopen elsewhere.
            if (ended == MAX_SUPERSEDED) throw SessionResumedElsewhereException(live.rootId, live.id)
            ended += 1
            close(orgId, live.id, EndSession(source = Session.SUPERSEDED))
        }
    }

    // S2-48: an agent token never closes a live launch's segment (nor its yes); a silent one, as the sweep would. The
    // segment is ended outside the transaction (its costs, gates, rules and receipt are other transactions), then
    // [replacing] runs the caller's transaction again: this one has written nothing yet.
    private fun supersede(open: Session, opening: Opening): Nothing {
        if (!opening.mayReplace && !silent(open)) throw NotYourSessionException()
        throw SupersededSegmentException(open)
    }

    private suspend fun found(
        tx: SessionsTransaction,
        last: Session,
        start: StartSession,
        labels: Map<String, String>,
    ): Session {
        if (last.launchId != null || start.launchId == null) return last
        val named = last.copy(
            launchId = start.launchId,
            packageDigest = last.packageDigest ?: start.packageDigest,
            labels = labels,
        )
        tx.update(named)
        return named
    }

    /** The labels of [launchId], none for no launch or when labels are not kept; read outside any transaction. */
    suspend fun labelsOf(orgId: Uuid, launchId: String?): Map<String, String> =
        if (labels == null || launchId == null) emptyMap() else labels.of(orgId, launchId)

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

        /** How many live segments one start may end before it gives up: each end lets another launch in. */
        const val MAX_SUPERSEDED: Int = 3

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

/** [SessionSegments.open] found [live] to end first: another launch's open segment, or one ended without a receipt. */
internal class SupersededSegmentException(val live: Session) : RuntimeException("superseded ${live.id}")
