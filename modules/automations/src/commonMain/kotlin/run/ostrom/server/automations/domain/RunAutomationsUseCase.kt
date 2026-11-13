// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.domain

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.errors.Pages
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The proof headers of a webhook request.
 *
 * @property timestamp `Ostrom-Timestamp`, Unix seconds.
 * @property signature `Ostrom-Signature`, `v1=<hex>` (several separated by commas).
 * @property deliveryKey `Idempotency-Key` or `Ostrom-Delivery`: a delivery is accepted once (S2-50).
 */
data class WebhookDelivery(val timestamp: String?, val signature: String?, val deliveryKey: String?)

/**
 * A run a trigger recorded, or found.
 *
 * @property run The run.
 * @property deduplicated Whether it is an earlier run with the same delivery key (nothing new started).
 */
data class Triggered(val run: AutomationRun, val deduplicated: Boolean)

/**
 * A payload variable as a dry run read it.
 *
 * @property name Its name.
 * @property value Its text (masked when sensitive), null when missing or refused.
 * @property sensitive Whether it is masked.
 */
data class DryRunVariable(val name: String, val value: String?, val sensitive: Boolean)

/**
 * What a run would do now, without running.
 *
 * @property renderedPrompt The prompt with its values (empty for a workflow action).
 * @property renderedInputs A workflow action's inputs with their values, those that render empty left out.
 * @property variables The payload variables.
 * @property problems Why the payload would be refused.
 * @property wouldSkip `disabled`, `budget` or `overlap` when a real trigger would not start now.
 * @property nextRuns The next three slots of a cron.
 */
data class DryRun(
    val renderedPrompt: String,
    val renderedInputs: Map<String, String>,
    val variables: List<DryRunVariable>,
    val problems: List<PayloadProblem>,
    val wouldSkip: String?,
    val nextRuns: List<Instant>,
)

/**
 * A page of runs.
 *
 * @property items The runs, newest first.
 * @property next The cursor of the next page.
 */
data class RunPage(val items: List<AutomationRun>, val next: Uuid?)

/** The `version` of a webhook URL that follows the automation's current version (`…/v/latest/trigger`). */
const val LATEST_VERSION = "latest"

/** Running automations: their webhooks, their tests, their crons, and their history. */
interface RunAutomationsUseCase {
    /**
     * A webhook request; its proof is checked before anything else.
     *
     * @param version What the URL pins: `latest` (the current version, as `/trigger`) or a version number, which the
     *   automation must still be at once the proof holds (TC-AUT-9).
     */
    suspend fun trigger(
        id: Uuid,
        delivery: WebhookDelivery,
        body: ByteArray,
        version: String = LATEST_VERSION,
    ): Triggered

    /** A real test of the saved definition, enabled or not (the owner only); [idempotencyKey] is required. */
    suspend fun test(caller: AutomationCaller, id: Uuid, payload: JsonObject?, idempotencyKey: String?): Triggered

    /** What a test would do, without a run nor a session (the owner only). */
    suspend fun dryRun(caller: AutomationCaller, id: Uuid, payload: JsonObject?): DryRun

    /** The runs of an automation the caller owns or administers. */
    suspend fun runs(caller: AutomationCaller, id: Uuid, before: Uuid?, limit: Int?): RunPage

    /**
     * Cancels a run of an automation the caller owns or administers: a queued run ends `cancelled` at once, a started
     * one has its session paused first (TC-AUT-17).
     *
     * @throws AutomationNotFoundException When the caller cannot see the automation.
     * @throws AutomationRunNotFoundException When it has no such run.
     * @throws AutomationRunEndedException When the run is over already.
     */
    suspend fun cancel(caller: AutomationCaller, id: Uuid, runId: Uuid): AutomationRun

    /**
     * One scheduler pass: expires the history, fails the runs queued too long, runs the due crons, then starts the
     * runs that waited for their owner's capacity.
     *
     * @return How many cron runs it recorded.
     */
    suspend fun tick(): Int
}

/**
 * [RunAutomationsUseCase] on the repository and the launcher.
 *
 * @param repository The automations and runs.
 * @param launcher Starts sessions.
 * @param secrets Checks webhook signatures.
 * @param members Tells whether an owner is still a member; every owner is when null (no directory bound).
 * @param clock The time.
 * @param maxRunning How many sessions of one member's automation runs may work at once; a run over it waits, queued.
 * @param workflows Starts, follows and cancels the workflow runs of workflow actions (TC-AUT-5).
 */
@Suppress("TooManyFunctions", "LongParameterList") // The steps of one run pipeline, shared by its three triggers; its ports.
class RunAutomationsUseCaseImpl(
    private val repository: AutomationsRepository,
    private val launcher: AutomationLauncher,
    private val secrets: WebhookSecrets,
    private val members: MemberDirectory?,
    private val clock: Clock,
    private val maxRunning: Int = DEFAULT_MAX_RUNNING,
    private val workflows: AutomationWorkflows = NoWorkflows,
) : RunAutomationsUseCase {
    override suspend fun trigger(id: Uuid, delivery: WebhookDelivery, body: ByteArray, version: String): Triggered {
        val now = clock.now().truncatedToMillis()
        val automation = repository.getById(id)?.takeIf { it.definition.trigger.kind == TriggerKind.WEBHOOK }
            ?: throw InvalidSignatureException()
        verify(automation, delivery, body, now)
        checkVersion(automation, version)
        if (!automation.enabled) throw AutomationDisabledException()
        val payload = payloadOf(body)
        val key = deliveryKey(delivery, body)
        val recent = repository.countRuns(
            automation.orgId,
            id,
            now - 1.minutes,
            setOf(RunSource.WEBHOOK),
            RunStatus.entries.toSet(),
        )
        if (recent >= MAX_PER_MINUTE) throw AutomationRateLimitException("At most $MAX_PER_MINUTE requests a minute.")
        val triggered = record(automation, RunSource.WEBHOOK, key, payload, now)
        // A delivery is accepted once (S2-50): its key is kept with its run, far longer than the 5 minutes a signed
        // timestamp is valid, so a captured request cannot be replayed.
        if (triggered.deduplicated) throw WebhookReplayException()
        if (triggered.run.status == RunStatus.SKIPPED_BUDGET) {
            throw AutomationRateLimitException("This automation used its runs of the day.")
        }
        return triggered
    }

    override suspend fun test(
        caller: AutomationCaller,
        id: Uuid,
        payload: JsonObject?,
        idempotencyKey: String?,
    ): Triggered {
        val automation = repository.ownedBy(caller, id)
        checkTestPayload(automation, payload)
        val key = idempotencyKey?.takeIf { DELIVERY_KEY.matches(it) } ?: throw InvalidAutomationException(
            "A test run needs an Idempotency-Key of 1 to 128 characters of A-Za-z0-9._:-.",
        )
        return record(automation, RunSource.TEST, "test:$key", payload, clock.now().truncatedToMillis())
    }

    override suspend fun dryRun(caller: AutomationCaller, id: Uuid, payload: JsonObject?): DryRun {
        val automation = repository.ownedBy(caller, id)
        checkTestPayload(automation, payload)
        val now = clock.now().truncatedToMillis()
        val runId = Uuid.random()
        val read = readOf(automation, payload)
        val wouldSkip = when {
            !automation.enabled -> "disabled"
            runsOfDay(automation, now) >= automation.definition.maxRunsPerDay -> "budget"
            overlapping(automation, runId) -> "overlap"
            else -> null
        }
        val values = runVariables(automation, runId, RunSource.TEST, now) + read.values
        return DryRun(
            renderedPrompt = Prompts.render(automation.definition.action.prompt, values),
            renderedInputs = renderInputs(automation.definition.action, values),
            variables = automation.definition.variables.map {
                val value = read.values[it.name]
                DryRunVariable(it.name, if (it.sensitive && value != null) MASK else value, it.sensitive)
            },
            problems = read.problems,
            wouldSkip = wouldSkip,
            nextRuns = AutomationRules.upcoming(automation.definition, now, NEXT_RUNS),
        )
    }

    override suspend fun runs(caller: AutomationCaller, id: Uuid, before: Uuid?, limit: Int?): RunPage {
        val automation = repository.visibleTo(caller, id)
        val size = limit ?: DEFAULT_PAGE
        requireAutomation(size in 1..MAX_PAGE) { "limit is between 1 and $MAX_PAGE." }
        // A `before` that names no run of this automation (forged, or another's) is a 400, not an empty page.
        val read = repository.listRuns(automation.orgId, automation.id, before, size + 1)
        val items = Pages.checked("before", before, read) { repository.hasRun(automation.orgId, automation.id, it) }
        return RunPage(items.take(size), items.getOrNull(size)?.let { items[size - 1].id })
    }

    override suspend fun cancel(caller: AutomationCaller, id: Uuid, runId: Uuid): AutomationRun {
        val automation = repository.visibleTo(caller, id)
        val run = repository.findRun(automation.orgId, automation.id, runId) ?: throw AutomationRunNotFoundException()
        when {
            run.status == RunStatus.QUEUED -> Unit
            run.status != RunStatus.STARTED -> throw AutomationRunEndedException()
            run.workflowRunId != null -> cancelWorkflowRun(automation, run.workflowRunId)
            else -> pauseSession(automation, run)
        }
        val now = clock.now().truncatedToMillis()
        val ended = run.copy(status = RunStatus.CANCELLED, reason = CANCELLED, updatedAt = now)
        if (!repository.endRun(ended, run.status)) throw AutomationRunEndedException()
        return ended
    }

    /** Cancels the workflow run of a started run; one already over means the run is over already. */
    private suspend fun cancelWorkflowRun(automation: Automation, workflowRunId: String) {
        if (!workflows.cancel(automation.orgId, automation.owner, workflowRunId)) throw AutomationRunEndedException()
    }

    /** Pauses the session of a started run; one that no longer works means the run is over already. */
    private suspend fun pauseSession(automation: Automation, run: AutomationRun) {
        val session = run.sessionId
        val paused = session != null && launcher.isWorking(automation.orgId, session) &&
            launcher.pause(automation.orgId, automation.owner, session)
        if (!paused) throw AutomationRunEndedException()
    }

    override suspend fun tick(): Int {
        val now = clock.now().truncatedToMillis()
        repository.deleteRunsBefore(now - HISTORY)
        repository.failQueuedBefore(now - QUEUE_TIMEOUT, now)
        val claimed = repository.claimDue(now, BATCH) { automation, slot -> claim(automation, slot, now) }
        for (entry in claimed) {
            proceed(entry.automation, entry.run, render(entry.automation, entry.run.id, RunSource.CRON, entry.slot))
        }
        startParked()
        followWorkflows()
        return claimed.size
    }

    /**
     * Ends the started runs of workflow actions whose workflow run ended: `succeeded`, `failed` (`workflow_failed`),
     * `cancelled` (`workflow_cancelled`), or `failed` (`workflow_missing`) when it no longer exists. A run cancelled
     * meanwhile stays cancelled (the write is conditional on `started`).
     */
    private suspend fun followWorkflows() {
        val now = clock.now().truncatedToMillis()
        for ((run, workflowRunId) in repository.followed(BATCH)) {
            val (status, reason) = when (workflows.state(run.orgId, workflowRunId)) {
                null -> RunStatus.FAILED to WORKFLOW_MISSING
                WorkflowRunState.RUNNING -> continue
                WorkflowRunState.SUCCEEDED -> RunStatus.SUCCEEDED to null
                WorkflowRunState.FAILED -> RunStatus.FAILED to "workflow_failed"
                WorkflowRunState.CANCELLED -> RunStatus.CANCELLED to "workflow_cancelled"
            }
            repository.endRun(run.copy(status = status, reason = reason, updatedAt = now), RunStatus.STARTED)
        }
    }

    /** Starts, oldest first, the runs that waited for their owner's capacity, while it has room. */
    private suspend fun startParked() {
        for (waiting in repository.parked(BATCH)) {
            // Its automation may be deleted since (its runs go with it, but not within one read).
            val automation = repository.get(waiting.orgId, waiting.automationId) ?: continue
            if (!atCapacity(automation)) startWaiting(automation, waiting)
        }
    }

    /** Takes the prompt of a waiting run, once (another replica that read the same run finds none), and starts it. */
    private suspend fun startWaiting(automation: Automation, waiting: AutomationRun) {
        val prompt = repository.takeParked(waiting.orgId, waiting.id) ?: return
        proceed(automation, waiting, prompt)
    }

    /** What a due slot does: run once if it is on time, or late within the catch-up rule; then the next slot. */
    private fun claim(automation: Automation, slot: Instant, now: Instant): CronClaim {
        val trigger = automation.definition.trigger
        val late = now - slot
        val skip = late > LATE && (trigger.catchup == Catchup.SKIP || late > trigger.catchupGraceMinutes.minutes)
        val next = try {
            AutomationRules.nextRun(automation.definition, now)
        } catch (expected: InvalidAutomationException) {
            // A schedule saved valid that no longer reads (a zone the time-zone database dropped, a cron an older
            // version accepted) pauses its automation without running the slot, rather than failing the claim of every
            // organization's due crons at each pass (TeamCode 9ade0c25). Enabling it again checks the definition.
            return CronClaim(nextRunAt = null, run = null, pause = true)
        }
        val run = if (skip) null else newRun(automation, RunSource.CRON, "cron:${slot.epochSeconds}", now, slot)
        return CronClaim(next, run)
    }

    /** Records a webhook or test run, then starts it unless its payload is refused or it is a duplicate. */
    private suspend fun record(
        automation: Automation,
        source: RunSource,
        key: String,
        payload: JsonObject?,
        now: Instant,
    ): Triggered {
        val read = readOf(automation, payload)
        val draft = newRun(automation, source, key, now, null).let {
            if (read.problems.isEmpty()) it else it.copy(status = RunStatus.INVALID_PAYLOAD, problems = read.problems)
        }
        val stored = repository.insertRun(draft)
        val duplicate = stored.id != draft.id
        val run = if (duplicate || stored.status == RunStatus.INVALID_PAYLOAD) {
            stored
        } else {
            proceed(automation, stored, render(automation, stored.id, source, now, read.values))
        }
        return Triggered(run, duplicate)
    }

    /**
     * Starts a queued run, records why it does not start, or leaves it queued with its prompt when its owner's
     * sessions of automation runs are at their cap (it starts when one ends, or fails after 30 minutes). A workflow
     * action never waits for that cap: its workflow runs are capped by their own rule (`run_limit`), as in TeamCode.
     */
    private suspend fun proceed(automation: Automation, run: AutomationRun, prompt: String): AutomationRun {
        val refused = refusal(automation, run)
        val session = automation.definition.action.workflowId == null
        if (refused == null && session && atCapacity(automation)) {
            repository.park(run.orgId, run.id, prompt)
            return run
        }
        val outcome = (refused ?: launch(automation, run, prompt)).copy(updatedAt = clock.now().truncatedToMillis())
        return if (repository.updateRun(outcome)) outcome else cancelledMeanwhile(automation, run, outcome)
    }

    /**
     * The run was cancelled while its session or workflow run was being created: that session is paused, that
     * workflow run cancelled, not left working.
     */
    private suspend fun cancelledMeanwhile(
        automation: Automation,
        run: AutomationRun,
        outcome: AutomationRun,
    ): AutomationRun {
        outcome.sessionId?.let { launcher.pause(automation.orgId, automation.owner, it) }
        outcome.workflowRunId?.let { workflows.cancel(automation.orgId, automation.owner, it) }
        return run.copy(status = RunStatus.CANCELLED, reason = CANCELLED, updatedAt = outcome.updatedAt)
    }

    /** The end of a queued run that must not start, or null when it may. */
    private suspend fun refusal(automation: Automation, run: AutomationRun): AutomationRun? = when {
        run.source != RunSource.TEST && runsOfDay(automation, run.createdAt) > automation.definition.maxRunsPerDay ->
            run.copy(status = RunStatus.SKIPPED_BUDGET)
        members?.isActiveMember(automation.orgId, automation.owner) == false ->
            run.copy(status = RunStatus.FAILED, reason = "owner_unavailable")
        automation.definition.overlap == Overlap.SKIP && overlapping(automation, run.id) ->
            run.copy(status = RunStatus.SKIPPED_OVERLAP)
        else -> null
    }

    /**
     * Whether the automation's owner already has [maxRunning] sessions of automation runs working: the sessions of
     * their started runs of the last day (a run lasts 24 hours at most) that still hold an execution, newest first.
     */
    private suspend fun atCapacity(automation: Automation): Boolean {
        val sessions = repository.startedSessions(
            automation.orgId,
            automation.owner,
            clock.now().truncatedToMillis() - WORKING_WINDOW,
            WORKING_SCAN,
        )
        var working = 0
        for (session in sessions) {
            if (launcher.isWorking(automation.orgId, session)) working++
            if (working >= maxRunning) return true
        }
        return false
    }

    /** A pinned version must be the automation's own, a `latest` is always. */
    private fun checkVersion(automation: Automation, version: String) {
        if (version == LATEST_VERSION) return
        val pinned = version.toIntOrNull() ?: throw InvalidAutomationException("A version is a number, or latest.")
        if (pinned != automation.version) throw AutomationVersionChangedException(pinned, automation.version)
    }

    private suspend fun launch(automation: Automation, run: AutomationRun, prompt: String): AutomationRun {
        val workflowId = automation.definition.action.workflowId
        if (workflowId != null) return startWorkflow(automation, run, workflowId, prompt)
        val request = LaunchRequest(
            orgId = automation.orgId,
            owner = automation.owner,
            automationId = automation.id,
            runId = run.id,
            name = automation.definition.name,
            action = automation.definition.action,
            prompt = prompt,
        )
        val result = try {
            launcher.launch(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The launcher's message may quote the prompt: only its type is kept, as a stable code.
            LaunchResult.Refused("launch_failed:" + error::class.simpleName.orEmpty())
        }
        return when (result) {
            is LaunchResult.Started -> run.copy(status = RunStatus.STARTED, sessionId = result.sessionId)
            is LaunchResult.Refused -> run.copy(status = RunStatus.FAILED, reason = result.reason)
        }
    }

    /**
     * Starts the workflow run of a workflow action as the owner, under the key `automation-run:<run id>`: a start
     * retried finds the first workflow run, never a second. A refusal ends the run `failed` with its code.
     *
     * @param rendered The rendered inputs, as [render] wrote them.
     */
    private suspend fun startWorkflow(
        automation: Automation,
        run: AutomationRun,
        workflowId: String,
        rendered: String,
    ): AutomationRun {
        val action = automation.definition.action
        val request = WorkflowStartRequest(
            orgId = automation.orgId,
            owner = automation.owner,
            automationId = automation.id,
            workflowId = workflowId,
            inputs = Json.decodeFromString(INPUTS, rendered),
            repository = action.repository,
            idempotencyKey = "automation-run:${run.id}",
        )
        val result = try {
            workflows.start(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The message may quote an input: only its type is kept, as a stable code.
            WorkflowStart.Refused("start_failed:" + error::class.simpleName.orEmpty())
        }
        return when (result) {
            is WorkflowStart.Started -> run.copy(status = RunStatus.STARTED, workflowRunId = result.workflowRunId)
            is WorkflowStart.Refused -> run.copy(status = RunStatus.FAILED, reason = result.reason)
        }
    }

    /** Cron and webhook runs that count towards the day's budget, recorded over the 24 hours before [at]. */
    private suspend fun runsOfDay(automation: Automation, at: Instant): Int = repository.countRuns(
        automation.orgId,
        automation.id,
        at - 1.days,
        setOf(RunSource.CRON, RunSource.WEBHOOK),
        setOf(RunStatus.QUEUED, RunStatus.STARTED, RunStatus.FAILED, RunStatus.CANCELLED, RunStatus.SUCCEEDED),
    )

    /** Whether the latest started run still works: its session, or its workflow run (waiting for a person too). */
    private suspend fun overlapping(automation: Automation, except: Uuid): Boolean =
        if (automation.definition.action.workflowId == null) {
            repository.latestSession(automation.orgId, automation.id, except)
                ?.let { launcher.isWorking(automation.orgId, it) } ?: false
        } else {
            repository.latestWorkflowRun(automation.orgId, automation.id, except)
                ?.let { workflows.state(automation.orgId, it) == WorkflowRunState.RUNNING } ?: false
        }

    private suspend fun verify(automation: Automation, delivery: WebhookDelivery, body: ByteArray, now: Instant) {
        val timestamp = delivery.timestamp?.toLongOrNull()
        if (timestamp == null || abs(now.epochSeconds - timestamp) > MAX_SKEW_SECONDS) throw InvalidSignatureException()
        val offered = delivery.signature.orEmpty().split(',').map { it.trim() }
            .filter { it.startsWith(SIGNATURE_PREFIX) }.map { it.removePrefix(SIGNATURE_PREFIX) }
        val generations = listOfNotNull(
            automation.secretGeneration,
            (automation.secretGeneration - 1).takeIf { automation.previousSecretValidUntil?.let { now < it } == true },
        )
        val signed = "$timestamp.".encodeToByteArray() + body
        val valid = generations.any { generation ->
            val expected = secrets.sign(secrets.secret(automation.id, generation), signed).toHexString()
            offered.any { constantTimeEquals(it.lowercase(), expected) }
        }
        if (!valid) throw InvalidSignatureException()
    }

    /** The sender's delivery id, or else the signed request itself: its timestamp and body. */
    private suspend fun deliveryKey(delivery: WebhookDelivery, body: ByteArray): String {
        val header = delivery.deliveryKey
        if (header == null) return "signed:" + secrets.digest("${delivery.timestamp}.".encodeToByteArray() + body)
        if (!DELIVERY_KEY.matches(header)) {
            throw InvalidWebhookPayloadException("A delivery key has 1 to 128 characters of A-Za-z0-9._:-.")
        }
        return "delivery:$header"
    }

    /**
     * What a run starts with: the rendered prompt of a session; the rendered inputs of a workflow action, as JSON (so
     * that a run waiting for capacity keeps one text either way).
     */
    private fun render(
        automation: Automation,
        runId: Uuid,
        source: RunSource,
        at: Instant,
        payload: Map<String, String> = emptyMap(),
    ): String {
        val values = runVariables(automation, runId, source, at) + payload
        val action = automation.definition.action
        return if (action.workflowId == null) {
            Prompts.render(action.prompt, values)
        } else {
            Json.encodeToString(INPUTS, renderInputs(action, values))
        }
    }

    /** Constants and readers of the enclosing type. */
    companion object {
        /** How many webhook requests an automation accepts a minute. */
        const val MAX_PER_MINUTE = 30

        /** How long the history keeps a run. */
        private val HISTORY = 14.days

        /** How long a run may stay queued before it is marked failed. */
        private val QUEUE_TIMEOUT = 30.minutes

        /** How many sessions of one member's automation runs may work at once (TeamCode's default). */
        const val DEFAULT_MAX_RUNNING = 2

        private const val CANCELLED = "cancelled"

        /** A workflow action whose workflow no longer exists. */
        private const val WORKFLOW_MISSING = "workflow_missing"

        private val INPUTS = MapSerializer(String.serializer(), String.serializer())

        /** How far back a started run may still have a working session: its runtime is at most 24 hours. */
        private val WORKING_WINDOW = 25.hours

        /** How many started runs are looked at to count the working sessions. */
        private const val WORKING_SCAN = 100

        /** A slot found later than this is a missed one, ruled by the catch-up. */
        private val LATE = 2.minutes

        private const val MAX_SKEW_SECONDS = 300L
        private const val SIGNATURE_PREFIX = "v1="
        private const val MASK = "••••"
        private const val NEXT_RUNS = 3
        private const val BATCH = 20
        private const val DEFAULT_PAGE = 50
        private const val MAX_PAGE = 100
        private val DELIVERY_KEY = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

/** A workflow action's inputs rendered with [values]; one that renders empty is left out (its default applies). */
private fun renderInputs(action: AutomationAction, values: Map<String, String>): Map<String, String> =
    action.inputs.mapValues { Prompts.render(it.value, values) }.filterValues { it.isNotBlank() }

/** A queued run of [automation]. */
private fun newRun(automation: Automation, source: RunSource, key: String, now: Instant, slot: Instant?) =
    AutomationRun(
        id = Uuid.random(),
        automationId = automation.id,
        orgId = automation.orgId,
        source = source,
        status = RunStatus.QUEUED,
        version = automation.version,
        deliveryKey = key,
        slotAt = slot,
        createdAt = now,
        updatedAt = now,
    )

private fun readOf(automation: Automation, payload: JsonObject?): ReadPayload =
    if (payload == null) {
        ReadPayload(emptyMap(), emptyList())
    } else {
        Prompts.read(payload, automation.definition.variables)
    }

/** A test of a webhook needs a payload; a cron's takes none. */
private fun checkTestPayload(automation: Automation, payload: JsonObject?) {
    val webhook = automation.definition.trigger.kind == TriggerKind.WEBHOOK
    requireAutomation(webhook == (payload != null)) {
        if (webhook) "A webhook test needs a payload." else "A cron test takes no payload."
    }
}

/** The body of a webhook: a JSON object of at most 64 KiB. */
private fun payloadOf(body: ByteArray): JsonObject {
    if (body.size > AutomationRules.MAX_PAYLOAD_BYTES) {
        throw InvalidWebhookPayloadException("The body is at most 64 KiB.")
    }
    val element = try {
        Json.parseToJsonElement(body.decodeToString())
    } catch (expected: SerializationException) {
        null
    }
    return element as? JsonObject ?: throw InvalidWebhookPayloadException("The body is a JSON object.")
}

/** The variables the server computes: the run, its time in the automation's zone (UTC for a webhook). */
private fun runVariables(automation: Automation, runId: Uuid, source: RunSource, at: Instant): Map<String, String> {
    val trigger = automation.definition.trigger
    val cron = trigger.kind == TriggerKind.CRON
    val zone = if (cron) AutomationRules.zone(trigger.timezone) else TimeZone.UTC
    val local = at.toLocalDateTime(zone)
    return mapOf(
        "run.id" to runId.toString(),
        "run.date" to local.date.toString(),
        "run.time" to local.hour.toString().padStart(2, '0') + ":" + local.minute.toString().padStart(2, '0'),
        "run.timezone" to zone.id,
        "run.source" to source.wire,
        "automation.id" to automation.id.toString(),
        "automation.name" to automation.definition.name,
    )
}
