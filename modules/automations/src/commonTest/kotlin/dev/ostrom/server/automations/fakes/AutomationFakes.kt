// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.fakes

import dev.ostrom.server.automations.domain.Automation
import dev.ostrom.server.automations.domain.AutomationAction
import dev.ostrom.server.automations.domain.AutomationCaller
import dev.ostrom.server.automations.domain.AutomationDefinition
import dev.ostrom.server.automations.domain.AutomationLauncher
import dev.ostrom.server.automations.domain.AutomationRun
import dev.ostrom.server.automations.domain.AutomationTrigger
import dev.ostrom.server.automations.domain.AutomationVariable
import dev.ostrom.server.automations.domain.AutomationsRepository
import dev.ostrom.server.automations.domain.ClaimedRun
import dev.ostrom.server.automations.domain.CronClaim
import dev.ostrom.server.automations.domain.LaunchRequest
import dev.ostrom.server.automations.domain.LaunchResult
import dev.ostrom.server.automations.domain.RunSource
import dev.ostrom.server.automations.domain.RunStatus
import dev.ostrom.server.automations.domain.TriggerKind
import dev.ostrom.server.automations.domain.WebhookSecrets
import kotlin.time.Instant
import kotlin.uuid.Uuid

val aliceAuto = AutomationCaller(ORG_A, ALICE.toString(), admin = false, interactive = true)
val aliceKey = AutomationCaller(ORG_A, ALICE.toString(), admin = false, interactive = false)
val bobAuto = AutomationCaller(ORG_A, BOB.toString(), admin = false, interactive = true)
val daveAuto = AutomationCaller(ORG_A, DAVE.toString(), admin = true, interactive = true)
val daveAutoKey = AutomationCaller(ORG_A, DAVE.toString(), admin = true, interactive = false)
val carolAuto = AutomationCaller(ORG_B, CAROL.toString(), admin = true, interactive = true)

/** A nightly cron in Paris. */
fun cronDefinition(name: String = "Nightly", prompt: String = "Summarize {{run.date}} at {{run.time}}") =
    AutomationDefinition(
        name = name,
        trigger = AutomationTrigger(TriggerKind.CRON, cron = "0 21 * * *", timezone = "Europe/Paris"),
        action = AutomationAction(prompt = prompt),
    )

/** A webhook reading a ticket number and an optional priority. */
fun webhookDefinition(name: String = "Ticket") = AutomationDefinition(
    name = name,
    trigger = AutomationTrigger(TriggerKind.WEBHOOK),
    action = AutomationAction(prompt = "Fix ticket {{ticket}} ({{priority}}) for {{automation.name}}"),
    variables = listOf(
        AutomationVariable("ticket", "$.ticket.number", maxLength = 8),
        AutomationVariable("priority", "$.priority", required = false, sensitive = true),
    ),
)

/** [AutomationsRepository] in memory. */
// The fake mirrors the SQL guards one early return at a time.
@Suppress("TooManyFunctions", "ReturnCount")
class InMemoryAutomations : AutomationsRepository {
    val automations = linkedMapOf<Uuid, Automation>()
    val runs = linkedMapOf<Uuid, AutomationRun>()
    var deletedBefore: Instant? = null
    var failedBefore: Instant? = null
    var loseNextUpdate = false
    var refuseNextDelete = false

    override suspend fun createWithinLimit(automation: Automation, maxPerOwner: Int): Boolean {
        if (automations.values.count { it.orgId == automation.orgId && it.owner == automation.owner } >= maxPerOwner) {
            return false
        }
        automations[automation.id] = automation
        return true
    }

    override suspend fun get(orgId: Uuid, id: Uuid): Automation? = automations[id]?.takeIf { it.orgId == orgId }

    override suspend fun getById(id: Uuid): Automation? = automations[id]

    override suspend fun list(orgId: Uuid, owner: String?, before: Uuid?, limit: Int): List<Automation> {
        val all = automations.values.reversed().filter { it.orgId == orgId && (owner == null || it.owner == owner) }
        val start = before?.let { cursor -> all.indexOfFirst { it.id == cursor } + 1 } ?: 0
        return all.drop(start).take(limit)
    }

    override suspend fun update(automation: Automation, expected: Automation): Automation? {
        if (loseNextUpdate) {
            loseNextUpdate = false
            return null
        }
        val current = automations[automation.id] ?: return null
        if (current.version != expected.version || current.updatedAt != expected.updatedAt) return null
        automations[automation.id] = automation
        return automation
    }

    override suspend fun delete(orgId: Uuid, id: Uuid): Boolean {
        if (refuseNextDelete) {
            refuseNextDelete = false
            return false
        }
        runs.values.removeAll { it.automationId == id }
        return automations.remove(id) != null
    }

    override suspend fun insertRun(run: AutomationRun): AutomationRun {
        findRunByDelivery(run.orgId, run.automationId, run.deliveryKey.orEmpty())?.let { return it }
        runs[run.id] = run
        return run
    }

    override suspend fun updateRun(run: AutomationRun) {
        runs[run.id] = run
    }

    override suspend fun findRunByDelivery(orgId: Uuid, automationId: Uuid, deliveryKey: String): AutomationRun? =
        runs.values.firstOrNull {
            it.orgId == orgId && it.automationId == automationId && it.deliveryKey == deliveryKey
        }

    override suspend fun listRuns(orgId: Uuid, automationId: Uuid, before: Uuid?, limit: Int): List<AutomationRun> {
        val all = runs.values.reversed().filter { it.orgId == orgId && it.automationId == automationId }
        val start = before?.let { cursor -> all.indexOfFirst { it.id == cursor } + 1 } ?: 0
        return all.drop(start).take(limit)
    }

    override suspend fun countRuns(
        orgId: Uuid,
        automationId: Uuid,
        since: Instant,
        sources: Set<RunSource>,
        statuses: Set<RunStatus>,
    ): Int = runs.values.count {
        it.automationId == automationId && it.createdAt > since && it.source in sources && it.status in statuses
    }

    override suspend fun latestSession(orgId: Uuid, automationId: Uuid, except: Uuid): String? = runs.values.reversed()
        .firstOrNull { it.automationId == automationId && it.status == RunStatus.STARTED && it.id != except }
        ?.sessionId

    override suspend fun claimDue(
        now: Instant,
        limit: Int,
        decide: suspend (Automation, Instant) -> CronClaim,
    ): List<ClaimedRun> {
        val due = automations.values.filter { it.enabled && it.nextRunAt?.let { at -> at <= now } == true }.take(limit)
        return due.mapNotNull { automation ->
            val slot = automation.nextRunAt!!
            val claim = decide(automation, slot)
            automations[automation.id] = automation.copy(nextRunAt = claim.nextRunAt)
            claim.run?.let { ClaimedRun(automation, insertRun(it), slot) }
        }
    }

    override suspend fun deleteRunsBefore(before: Instant) {
        deletedBefore = before
        runs.values.removeAll { it.createdAt < before }
    }

    override suspend fun failQueuedBefore(before: Instant, now: Instant) {
        failedBefore = before
        runs.entries.filter { (_, run) -> run.status == RunStatus.QUEUED && run.createdAt < before }.forEach { entry ->
            entry.setValue(entry.value.copy(status = RunStatus.FAILED, reason = "interrupted", updatedAt = now))
        }
    }
}

/** Records what it is asked to start; [next] decides the outcome. */
class RecordingLauncher : AutomationLauncher {
    val launched = mutableListOf<LaunchRequest>()
    val working = mutableSetOf<String>()
    var next: (LaunchRequest) -> LaunchResult = { LaunchResult.Started("session-${launched.size}") }

    override suspend fun launch(request: LaunchRequest): LaunchResult {
        launched += request
        return next(request)
    }

    override suspend fun isWorking(orgId: Uuid, sessionId: String): Boolean = sessionId in working
}

/** Secrets that read as what they are, and a signature that is the secret and the data joined. */
object PlainSecrets : WebhookSecrets {
    override suspend fun secret(automationId: Uuid, generation: Int): String = "whsec_${automationId}_$generation"

    override suspend fun sign(secret: String, data: ByteArray): ByteArray = (secret + "|").encodeToByteArray() + data

    override suspend fun digest(data: ByteArray): String = "digest-" + data.decodeToString()
}
