// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.domain

import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The automations and their runs. A name is unique per owner: a duplicate is refused by the store (409). */
@Suppress("TooManyFunctions") // One port for the two tables of a feature, as WaitsRepository.
interface AutomationsRepository {
    /** Stores [automation] unless its owner has [maxPerOwner] already; false then. */
    suspend fun createWithinLimit(automation: Automation, maxPerOwner: Int): Boolean

    /** The automation [id] of [orgId]. */
    suspend fun get(orgId: Uuid, id: Uuid): Automation?

    /** The automation [id], whatever its organization (the webhook knows no organization before its proof). */
    suspend fun getById(id: Uuid): Automation?

    /** The automations of [orgId], of [owner] when given, newest first, after [before]. */
    suspend fun list(orgId: Uuid, owner: String?, before: Uuid?, limit: Int): List<Automation>

    /**
     * Writes [automation] if the stored one is still [expected] (same version and last change).
     *
     * @return The written automation, or null when it changed or is gone.
     */
    suspend fun update(automation: Automation, expected: Automation): Automation?

    /** Deletes the automation and its runs; false when there was none. */
    suspend fun delete(orgId: Uuid, id: Uuid): Boolean

    /**
     * Records [run], unless its automation already has a run with the same delivery key.
     *
     * @return [run], or the earlier run with its delivery key.
     */
    suspend fun insertRun(run: AutomationRun): AutomationRun

    /** Writes the status of [run]. */
    suspend fun updateRun(run: AutomationRun)

    /** The run of [automationId] (of [orgId]) with [deliveryKey]. */
    suspend fun findRunByDelivery(orgId: Uuid, automationId: Uuid, deliveryKey: String): AutomationRun?

    /** The runs of [automationId], newest first, after [before]. */
    suspend fun listRuns(orgId: Uuid, automationId: Uuid, before: Uuid?, limit: Int): List<AutomationRun>

    /** How many runs of [automationId] (of [orgId]) from [sources] and in [statuses] were recorded since [since]. */
    suspend fun countRuns(
        orgId: Uuid,
        automationId: Uuid,
        since: Instant,
        sources: Set<RunSource>,
        statuses: Set<RunStatus>,
    ): Int

    /** The session of the latest started run of [automationId] (of [orgId]) other than [except]. */
    suspend fun latestSession(orgId: Uuid, automationId: Uuid, except: Uuid): String?

    /**
     * Claims the enabled crons due at [now] (at most [limit], each by one replica): for each, [decide] gives its next
     * slot and the run to record, both written in the claim's transaction.
     *
     * @return The runs recorded, with their automation and slot.
     */
    suspend fun claimDue(
        now: Instant,
        limit: Int,
        decide: suspend (Automation, Instant) -> CronClaim,
    ): List<ClaimedRun>

    /** Deletes the runs recorded before [before] (the history keeps 14 days). */
    suspend fun deleteRunsBefore(before: Instant)

    /** Marks failed (`interrupted`) the runs still queued since before [before]. */
    suspend fun failQueuedBefore(before: Instant, now: Instant)
}

/**
 * What a due cron does.
 *
 * @property nextRunAt Its next slot (none when it never runs again).
 * @property run The run to record, or none when the slot is skipped.
 */
data class CronClaim(val nextRunAt: Instant?, val run: AutomationRun?)

/**
 * A cron run recorded by a claim.
 *
 * @property automation Its automation.
 * @property run The run, queued.
 * @property slot The slot it runs for.
 */
data class ClaimedRun(val automation: Automation, val run: AutomationRun, val slot: Instant)

/**
 * A cloud session to start for a run, as the automation's owner.
 *
 * @property orgId The organization.
 * @property owner The member it runs as.
 * @property automationId The automation (a label of the session).
 * @property runId The run.
 * @property name The session's name.
 * @property action The agent, model, effort and runtime cap.
 * @property prompt The rendered prompt.
 */
data class LaunchRequest(
    val orgId: Uuid,
    val owner: String,
    val automationId: Uuid,
    val runId: Uuid,
    val name: String,
    val action: AutomationAction,
    val prompt: String,
)

/** What starting a session gave. */
sealed interface LaunchResult {
    /** The session exists. */
    data class Started(val sessionId: String) : LaunchResult

    /** It could not be created; [reason] is a stable code (`cloud_session_limit`…). */
    data class Refused(val reason: String) : LaunchResult
}

/** Starts the sessions of runs; bound by the module that serves cloud sessions. */
interface AutomationLauncher {
    /** Starts a session. */
    suspend fun launch(request: LaunchRequest): LaunchResult

    /** Whether the session still works (an overlapping trigger is then skipped). */
    suspend fun isWorking(orgId: Uuid, sessionId: String): Boolean
}

/** Webhook secrets and signatures. */
interface WebhookSecrets {
    /** The secret of [automationId] at [generation] (`whsec_…`), derived again at each call, never stored. */
    suspend fun secret(automationId: Uuid, generation: Int): String

    /** HMAC-SHA256 of [data] keyed with [secret]. */
    suspend fun sign(secret: String, data: ByteArray): ByteArray

    /** The SHA-256 of [data], in hexadecimal. */
    suspend fun digest(data: ByteArray): String
}
