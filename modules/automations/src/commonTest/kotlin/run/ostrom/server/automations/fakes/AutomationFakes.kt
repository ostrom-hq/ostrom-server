// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.fakes

import run.ostrom.server.automations.domain.Automation
import run.ostrom.server.automations.domain.AutomationAction
import run.ostrom.server.automations.domain.AutomationCaller
import run.ostrom.server.automations.domain.AutomationDefinition
import run.ostrom.server.automations.domain.AutomationLauncher
import run.ostrom.server.automations.domain.AutomationRun
import run.ostrom.server.automations.domain.AutomationTrigger
import run.ostrom.server.automations.domain.AutomationVariable
import run.ostrom.server.automations.domain.AutomationWorkflows
import run.ostrom.server.automations.domain.WorkflowInputSpec
import run.ostrom.server.automations.domain.WorkflowRunState
import run.ostrom.server.automations.domain.WorkflowStart
import run.ostrom.server.automations.domain.WorkflowStartRequest
import run.ostrom.server.automations.domain.AutomationsRepository
import run.ostrom.server.automations.domain.ClaimedRun
import run.ostrom.server.automations.domain.CronClaim
import run.ostrom.server.automations.domain.FollowedRun
import run.ostrom.server.automations.domain.LaunchRequest
import run.ostrom.server.automations.domain.LaunchResult
import run.ostrom.server.automations.domain.RunSource
import run.ostrom.server.automations.domain.RunStatus
import run.ostrom.server.automations.domain.TriggerKind
import run.ostrom.server.automations.domain.WebhookSecrets
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The credential of a test's request. */
val testActor = AuthenticatedPrincipal("alice", PrincipalKind.USER, setOf("automations:write"))

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
    val prompts = linkedMapOf<Uuid, String>()
    var takenElsewhere = false

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
        // None after a row it does not hold, as the database.
        val start = before?.let { cursor -> all.indexOfFirst { it.id == cursor } + 1 } ?: 0
        return if (before != null && start == 0) emptyList() else all.drop(start).take(limit)
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

    override suspend fun updateRun(run: AutomationRun): Boolean {
        if (runs[run.id]?.status != RunStatus.QUEUED) return false
        runs[run.id] = run
        prompts.remove(run.id)
        return true
    }

    override suspend fun endRun(run: AutomationRun, expected: RunStatus): Boolean {
        if (runs[run.id]?.status != expected) return false
        runs[run.id] = run
        prompts.remove(run.id)
        return true
    }

    override suspend fun findRun(orgId: Uuid, automationId: Uuid, id: Uuid): AutomationRun? =
        runs[id]?.takeIf { it.orgId == orgId && it.automationId == automationId }

    override suspend fun park(orgId: Uuid, runId: Uuid, prompt: String) {
        if (runs[runId]?.status == RunStatus.QUEUED) prompts[runId] = prompt
    }

    override suspend fun parked(limit: Int): List<AutomationRun> {
        failIf("parked")
        return runs.values.filter { it.status == RunStatus.QUEUED && it.id in prompts }.take(limit)
    }

    override suspend fun takeParked(orgId: Uuid, runId: Uuid): String? =
        if (takenElsewhere) null else prompts.remove(runId)

    override suspend fun startedSessions(orgId: Uuid, owner: String, since: Instant, limit: Int): List<String> =
        runs.values.reversed().filter {
            it.orgId == orgId && it.status == RunStatus.STARTED && it.createdAt > since &&
                automations[it.automationId]?.owner == owner
        }.mapNotNull { it.sessionId }.take(limit)

    /** The scheduler steps that throw, by the name of their method. */
    val failing = mutableSetOf<String>()

    private fun failIf(step: String) {
        if (step in failing) throw IllegalStateException(step)
    }

    /** When each run was last followed, as [followed] marks it. */
    val followedAt = mutableMapOf<Uuid, Instant>()

    override suspend fun followed(now: Instant, limit: Int): List<FollowedRun> {
        failIf("followed")
        return runs.values
            .filter { it.status == RunStatus.STARTED && it.workflowRunId != null }
            .groupBy { it.orgId }.values
            .flatMap { ofOrg -> ofOrg.sortedBy { followedAt[it.id] ?: Instant.DISTANT_PAST }.take(limit) }
            .onEach { followedAt[it.id] = now }
            .map { FollowedRun(it, it.workflowRunId.orEmpty()) }
    }

    override suspend fun latestWorkflowRun(orgId: Uuid, automationId: Uuid, except: Uuid): String? =
        runs.values.reversed()
            .firstOrNull {
                it.automationId == automationId && it.status == RunStatus.STARTED && it.workflowRunId != null &&
                    it.id != except
            }?.workflowRunId

    override suspend fun automationStarting(orgId: Uuid, workflowId: String): String? = automations.values
        .filter { it.orgId == orgId && it.definition.action.workflowId == workflowId }
        .minOfOrNull { it.definition.name }

    override suspend fun findRunByDelivery(orgId: Uuid, automationId: Uuid, deliveryKey: String): AutomationRun? =
        runs.values.firstOrNull {
            it.orgId == orgId && it.automationId == automationId && it.deliveryKey == deliveryKey
        }

    override suspend fun listRuns(orgId: Uuid, automationId: Uuid, before: Uuid?, limit: Int): List<AutomationRun> {
        val all = runs.values.reversed().filter { it.orgId == orgId && it.automationId == automationId }
        // None after a row it does not hold, as the database.
        val start = before?.let { cursor -> all.indexOfFirst { it.id == cursor } + 1 } ?: 0
        return if (before != null && start == 0) emptyList() else all.drop(start).take(limit)
    }

    override suspend fun hasRun(orgId: Uuid, automationId: Uuid, id: Uuid): Boolean =
        runs[id]?.let { it.orgId == orgId && it.automationId == automationId } == true

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
        .firstOrNull {
            it.automationId == automationId && it.status == RunStatus.STARTED && it.sessionId != null && it.id != except
        }?.sessionId

    override suspend fun claimDue(
        now: Instant,
        limit: Int,
        decide: suspend (Automation, Instant) -> CronClaim,
    ): List<ClaimedRun> {
        failIf("claimDue")
        val due = automations.values.filter { it.enabled && it.nextRunAt?.let { at -> at <= now } == true }.take(limit)
        return due.mapNotNull { automation ->
            val slot = automation.nextRunAt!!
            val claim = decide(automation, slot)
            automations[automation.id] = automation.copy(
                enabled = automation.enabled && !claim.pause,
                nextRunAt = claim.nextRunAt,
            )
            claim.run?.let { ClaimedRun(automation, insertRun(it), slot) }
        }
    }

    override suspend fun deleteRunsBefore(before: Instant) {
        failIf("deleteRunsBefore")
        deletedBefore = before
        runs.values.removeAll { it.createdAt < before }
    }

    override suspend fun failQueuedBefore(before: Instant, now: Instant) {
        failIf("failQueuedBefore")
        failedBefore = before
        runs.entries.filter { (_, run) -> run.status == RunStatus.QUEUED && run.createdAt < before }.forEach { entry ->
            val reason = if (prompts.remove(entry.key) == null) "interrupted" else "capacity_timeout"
            entry.setValue(entry.value.copy(status = RunStatus.FAILED, reason = reason, updatedAt = now))
        }
    }
}

/** Records what it is asked to start; [next] decides the outcome. */
class RecordingLauncher : AutomationLauncher {
    val launched = mutableListOf<LaunchRequest>()
    val working = mutableSetOf<String>()
    val paused = mutableListOf<String>()
    var pauses = true
    var onPause: () -> Unit = {}
    var next: (LaunchRequest) -> LaunchResult = { LaunchResult.Started("session-${launched.size}") }

    override suspend fun launch(request: LaunchRequest): LaunchResult {
        launched += request
        return next(request)
    }

    override suspend fun isWorking(orgId: Uuid, sessionId: String): Boolean = sessionId in working

    override suspend fun pause(orgId: Uuid, owner: String, sessionId: String): Boolean {
        paused += "$owner:$sessionId"
        onPause()
        return pauses
    }
}

/** Secrets that read as what they are, and a signature that is the secret and the data joined. */
object PlainSecrets : WebhookSecrets {
    override suspend fun secret(automationId: Uuid, generation: Int): String = "whsec_${automationId}_$generation"

    override suspend fun sign(secret: String, data: ByteArray): ByteArray = (secret + "|").encodeToByteArray() + data

    override suspend fun digest(data: ByteArray): String = "digest-" + data.decodeToString()
}

/** Workflows in memory: [inputs] by workflow id, [states] by workflow run id; records starts and cancels. */
class RecordingWorkflows : AutomationWorkflows {
    val inputs = mutableMapOf<String, List<WorkflowInputSpec>>()
    val started = mutableListOf<WorkflowStartRequest>()
    val states = mutableMapOf<String, WorkflowRunState>()
    val cancelled = mutableListOf<String>()
    var cancels = true
    var next: (WorkflowStartRequest) -> WorkflowStart = { WorkflowStart.Started("wfr_${started.size}") }

    override suspend fun inputs(orgId: Uuid, workflowId: String): List<WorkflowInputSpec>? = inputs[workflowId]

    override suspend fun start(request: WorkflowStartRequest): WorkflowStart {
        started += request
        return next(request)
    }

    /** What reading the state of a workflow run throws, by its id. */
    val failures = mutableMapOf<String, Exception>()

    override suspend fun state(orgId: Uuid, workflowRunId: String): WorkflowRunState? {
        failures[workflowRunId]?.let { throw it }
        return states[workflowRunId]
    }

    override suspend fun cancel(orgId: Uuid, owner: String, workflowRunId: String): Boolean {
        cancelled += "$owner:$workflowRunId"
        return cancels
    }
}

/** A nightly cron that starts the workflow `triage` with [inputs]. */
fun workflowDefinition(
    inputs: Map<String, String> = mapOf("ticket" to "T-{{run.date}}", "note" to ""),
    repository: String? = "acme/api",
) = AutomationDefinition(
    name = "Triage",
    trigger = AutomationTrigger(TriggerKind.CRON, cron = "0 21 * * *", timezone = "Europe/Paris"),
    action = AutomationAction(prompt = "", workflowId = "triage", inputs = inputs, repository = repository),
)
