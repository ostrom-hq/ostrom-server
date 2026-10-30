// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.domain

import kotlinx.coroutines.CancellationException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The proof headers of a webhook request.
 *
 * @property timestamp `Ostrom-Timestamp`, Unix seconds.
 * @property signature `Ostrom-Signature`, `v1=<hex>` (several separated by commas).
 * @property deliveryKey `Idempotency-Key` or `Ostrom-Delivery`, which deduplicates retries.
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
 * @property renderedPrompt The prompt with its values.
 * @property variables The payload variables.
 * @property problems Why the payload would be refused.
 * @property wouldSkip `disabled`, `budget` or `overlap` when a real trigger would not start now.
 * @property nextRuns The next three slots of a cron.
 */
data class DryRun(
    val renderedPrompt: String,
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

/** Running automations: their webhooks, their tests, their crons, and their history. */
interface RunAutomationsUseCase {
    /** A webhook request; its proof is checked before anything else. */
    suspend fun trigger(id: Uuid, delivery: WebhookDelivery, body: ByteArray): Triggered

    /** A real test of the saved definition, enabled or not (the owner only); [idempotencyKey] is required. */
    suspend fun test(caller: AutomationCaller, id: Uuid, payload: JsonObject?, idempotencyKey: String?): Triggered

    /** What a test would do, without a run nor a session (the owner only). */
    suspend fun dryRun(caller: AutomationCaller, id: Uuid, payload: JsonObject?): DryRun

    /** The runs of an automation the caller owns or administers. */
    suspend fun runs(caller: AutomationCaller, id: Uuid, before: Uuid?, limit: Int?): RunPage

    /**
     * One scheduler pass: expires the history, fails the runs queued too long, then runs the due crons.
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
 */
@Suppress("TooManyFunctions") // The steps of one run pipeline, shared by its three triggers.
class RunAutomationsUseCaseImpl(
    private val repository: AutomationsRepository,
    private val launcher: AutomationLauncher,
    private val secrets: WebhookSecrets,
    private val members: MemberDirectory?,
    private val clock: Clock,
) : RunAutomationsUseCase {
    override suspend fun trigger(id: Uuid, delivery: WebhookDelivery, body: ByteArray): Triggered {
        val now = clock.now().truncatedToMillis()
        val automation = repository.getById(id)?.takeIf { it.definition.trigger.kind == TriggerKind.WEBHOOK }
            ?: throw InvalidSignatureException()
        verify(automation, delivery, body, now)
        if (!automation.enabled) throw AutomationDisabledException()
        val payload = payloadOf(body)
        val key = deliveryKey(delivery.deliveryKey, body, now)
        repository.findRunByDelivery(id, key)?.let { return Triggered(it, deduplicated = true) }
        val recent = repository.countRuns(id, now - 1.minutes, setOf(RunSource.WEBHOOK), RunStatus.entries.toSet())
        if (recent >= MAX_PER_MINUTE) throw AutomationRateLimitException("At most $MAX_PER_MINUTE requests a minute.")
        val triggered = record(automation, RunSource.WEBHOOK, key, payload, now)
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
        return DryRun(
            renderedPrompt = render(automation, runId, RunSource.TEST, now, read.values),
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
        val items = repository.listRuns(automation.orgId, automation.id, before, size + 1)
        return RunPage(items.take(size), items.getOrNull(size)?.let { items[size - 1].id })
    }

    override suspend fun tick(): Int {
        val now = clock.now().truncatedToMillis()
        repository.deleteRunsBefore(now - HISTORY)
        repository.failQueuedBefore(now - QUEUE_TIMEOUT, now)
        val claimed = repository.claimDue(now, BATCH) { automation, slot -> claim(automation, slot, now) }
        for (entry in claimed) {
            proceed(entry.automation, entry.run, render(entry.automation, entry.run.id, RunSource.CRON, entry.slot))
        }
        return claimed.size
    }

    /** What a due slot does: run once if it is on time, or late within the catch-up rule; then the next slot. */
    private fun claim(automation: Automation, slot: Instant, now: Instant): CronClaim {
        val trigger = automation.definition.trigger
        val late = now - slot
        val skip = late > LATE && (trigger.catchup == Catchup.SKIP || late > trigger.catchupGraceMinutes.minutes)
        val run = if (skip) null else newRun(automation, RunSource.CRON, "cron:${slot.epochSeconds}", now, slot)
        return CronClaim(AutomationRules.nextRun(automation.definition, now), run)
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

    /** Starts a queued run, or records why it does not start. */
    private suspend fun proceed(automation: Automation, run: AutomationRun, prompt: String): AutomationRun {
        val definition = automation.definition
        val outcome = when {
            run.source != RunSource.TEST && runsOfDay(automation, run.createdAt) > definition.maxRunsPerDay ->
                run.copy(status = RunStatus.SKIPPED_BUDGET)
            members?.isActiveMember(automation.orgId, automation.owner) == false ->
                run.copy(status = RunStatus.FAILED, reason = "owner_unavailable")
            definition.overlap == Overlap.SKIP && overlapping(automation, run.id) ->
                run.copy(status = RunStatus.SKIPPED_OVERLAP)
            else -> launch(automation, run, prompt)
        }.copy(updatedAt = clock.now().truncatedToMillis())
        repository.updateRun(outcome)
        return outcome
    }

    private suspend fun launch(automation: Automation, run: AutomationRun, prompt: String): AutomationRun {
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

    /** Cron and webhook runs that count towards the day's budget, recorded over the 24 hours before [at]. */
    private suspend fun runsOfDay(automation: Automation, at: Instant): Int = repository.countRuns(
        automation.id,
        at - 1.days,
        setOf(RunSource.CRON, RunSource.WEBHOOK),
        setOf(RunStatus.QUEUED, RunStatus.STARTED, RunStatus.FAILED),
    )

    private suspend fun overlapping(automation: Automation, except: Uuid): Boolean =
        repository.latestSession(automation.id, except)?.let { launcher.isWorking(automation.orgId, it) } ?: false

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

    private suspend fun deliveryKey(header: String?, body: ByteArray, now: Instant): String {
        if (header == null) return "body:" + secrets.digest(body) + ":" + now.epochSeconds / SECONDS_PER_MINUTE
        if (!DELIVERY_KEY.matches(header)) {
            throw InvalidWebhookPayloadException("A delivery key has 1 to 128 characters of A-Za-z0-9._:-.")
        }
        return "delivery:$header"
    }

    private fun render(
        automation: Automation,
        runId: Uuid,
        source: RunSource,
        at: Instant,
        payload: Map<String, String> = emptyMap(),
    ): String {
        val values = runVariables(automation, runId, source, at) + payload
        return Prompts.render(automation.definition.action.prompt, values)
    }

    companion object {
        /** How many webhook requests an automation accepts a minute. */
        const val MAX_PER_MINUTE = 30

        /** How long the history keeps a run. */
        val HISTORY = 14.days

        /** How long a run may stay queued before it is marked failed. */
        val QUEUE_TIMEOUT = 30.minutes

        /** A slot found later than this is a missed one, ruled by the catch-up. */
        val LATE = 2.minutes

        private const val MAX_SKEW_SECONDS = 300L
        private const val SECONDS_PER_MINUTE = 60
        private const val SIGNATURE_PREFIX = "v1="
        private const val MASK = "••••"
        private const val NEXT_RUNS = 3
        private const val BATCH = 20
        private const val DEFAULT_PAGE = 50
        private const val MAX_PAGE = 100
        private val DELIVERY_KEY = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

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
    payload?.let { Prompts.read(it, automation.definition.variables) } ?: ReadPayload(emptyMap(), emptyList())

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
    val zone = if (cron) AutomationRules.zone(trigger.timezone.orEmpty()) else TimeZone.UTC
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

/** Compares two strings in a time that does not depend on where they differ. */
private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var difference = 0
    for (index in a.indices) difference = difference or (a[index].code xor b[index].code)
    return difference == 0
}
