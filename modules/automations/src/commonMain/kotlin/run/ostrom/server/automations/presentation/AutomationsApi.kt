// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.presentation

import run.ostrom.server.automations.domain.Automation
import run.ostrom.server.automations.domain.AutomationAction
import run.ostrom.server.automations.domain.AutomationDefinition
import run.ostrom.server.automations.domain.AutomationRun
import run.ostrom.server.automations.domain.AutomationTrigger
import run.ostrom.server.automations.domain.AutomationVariable
import run.ostrom.server.automations.domain.DryRun
import run.ostrom.server.automations.domain.InvalidAutomationException
import run.ostrom.server.automations.domain.Overlap
import run.ostrom.server.automations.domain.PayloadProblem
import run.ostrom.server.automations.domain.TriggerKind
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * `GET /api/v1/automations` and `POST` (create), as in ostrom-core's `api/openapi.yaml` (operations
 * `listAutomations`, `createAutomation`).
 *
 * @property owner `all` or a member id, for an administrator.
 * @property before The cursor of the previous page.
 * @property limit The page size, 1 to 100.
 */
@Serializable
@Resource("/api/v1/automations")
class AutomationsApi(val owner: String? = null, val before: String? = null, val limit: Int? = null) {
    /**
     * `GET`, `PUT` and `DELETE /api/v1/automations/{id}`.
     *
     * @property parent The collection.
     * @property id The automation id.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: AutomationsApi = AutomationsApi(), val id: String) {
        /** `POST …/enable`. */
        @Serializable
        @Resource("enable")
        class Enable(val parent: Id)

        /** `POST …/disable`. */
        @Serializable
        @Resource("disable")
        class Disable(val parent: Id)

        /** `POST …/webhook/rotate-secret`. */
        @Serializable
        @Resource("webhook/rotate-secret")
        class RotateSecret(val parent: Id)

        /** `POST …/run`: a test. */
        @Serializable
        @Resource("run")
        class Run(val parent: Id)

        /**
         * `GET …/runs`.
         *
         * @property before The cursor.
         * @property limit The page size.
         */
        @Serializable
        @Resource("runs")
        class Runs(val parent: Id, val before: String? = null, val limit: Int? = null)

        /** `POST …/trigger`: the signed webhook, without a login. */
        @Serializable
        @Resource("trigger")
        class Trigger(val parent: Id)

        /**
         * `POST …/v/{version}/trigger`: the signed webhook pinned to a version of the definition, or to `latest`.
         *
         * @property version A version number, or `latest`.
         */
        @Serializable
        @Resource("v/{version}/trigger")
        class VersionTrigger(val parent: Id, val version: String)

        /**
         * `POST …/runs/{runId}/cancel`.
         *
         * @property runId The run.
         */
        @Serializable
        @Resource("runs/{runId}/cancel")
        class CancelRun(val parent: Id, val runId: String)
    }
}

/**
 * The action of a request: a prompt for a cloud agent session. `workflowId` is in the contract and refused until
 * workflow runs can be started.
 */
@Serializable
data class AutomationActionRequest(
    val workflowId: String? = null,
    val prompt: String? = null,
    val agent: String = AutomationAction.DEFAULT_AGENT,
    val model: String? = null,
    val effort: String? = null,
    val maxRuntimeMinutes: Int = AutomationAction.DEFAULT_RUNTIME_MINUTES,
)

/** The body of `POST` and `PUT /api/v1/automations[/{id}]`; `version` is the one read, required by `PUT`. */
@Serializable
data class AutomationRequest(
    val name: String,
    val trigger: AutomationTrigger,
    val action: AutomationActionRequest,
    val variables: List<AutomationVariable> = emptyList(),
    val samplePayload: JsonObject? = null,
    val overlap: String = Overlap.SKIP.wire,
    val maxRunsPerDay: Int = AutomationDefinition.DEFAULT_MAX_RUNS_PER_DAY,
    val version: Int? = null,
)

/** The definition of a request. */
fun AutomationRequest.toDefinition(): AutomationDefinition {
    if (action.workflowId != null) {
        throw InvalidAutomationException("Workflow actions are not available yet: give the action a prompt.")
    }
    val overlapMode = when (overlap) {
        Overlap.SKIP.wire -> Overlap.SKIP
        Overlap.ALLOW.wire -> Overlap.ALLOW
        else -> throw InvalidAutomationException("overlap is skip or allow.")
    }
    return AutomationDefinition(
        name = name,
        trigger = trigger,
        action = AutomationAction(
            prompt = action.prompt.orEmpty(),
            agent = action.agent,
            model = action.model,
            effort = action.effort,
            maxRuntimeMinutes = action.maxRuntimeMinutes,
        ),
        variables = variables,
        samplePayload = samplePayload,
        overlap = overlapMode,
        maxRunsPerDay = maxRunsPerDay,
    )
}

/** An automation, as the contract's `Automation`; `secret` only at creation and rotation of a webhook. */
@Serializable
data class AutomationResponse(
    val id: String,
    val name: String,
    val owner: String,
    val enabled: Boolean,
    val trigger: AutomationTrigger,
    val action: AutomationAction,
    val variables: List<AutomationVariable>,
    val samplePayload: JsonObject?,
    val overlap: String,
    val maxRunsPerDay: Int,
    val webhookUrl: String?,
    val version: Int,
    val nextRunAt: String?,
    val previousSecretValidUntil: String?,
    val createdAt: String,
    val updatedAt: String,
    val secret: String? = null,
)

/** The response of an automation, whose webhook URL is under [publicUrl] (relative when empty). */
fun Automation.toResponse(publicUrl: String, secret: String? = null): AutomationResponse = AutomationResponse(
    id = id.toString(),
    name = definition.name,
    owner = owner,
    enabled = enabled,
    trigger = definition.trigger,
    action = definition.action,
    variables = definition.variables,
    samplePayload = definition.samplePayload,
    overlap = definition.overlap.wire,
    maxRunsPerDay = definition.maxRunsPerDay,
    webhookUrl = "$publicUrl/api/v1/automations/$id/trigger".takeIf { definition.trigger.kind == TriggerKind.WEBHOOK },
    version = version,
    nextRunAt = nextRunAt?.toString(),
    previousSecretValidUntil = previousSecretValidUntil?.toString(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    secret = secret,
)

/** A page of automations. */
@Serializable
data class AutomationsResponse(val items: List<AutomationResponse>, val next: String?)

/** The response of `…/webhook/rotate-secret`: the new secret, shown once. */
@Serializable
data class SecretResponse(val secret: String, val previousSecretValidUntil: String?)

/** The body of `POST …/run`. */
@Serializable
data class TestRunRequest(val payload: JsonObject? = null, val dryRun: Boolean = false)

/** A run. */
@Serializable
data class RunResponse(
    val id: String,
    val automationId: String,
    val source: String,
    val status: String,
    val version: Int,
    val sessionId: String?,
    val reason: String?,
    val problems: List<PayloadProblem>,
    val slotAt: String?,
    val createdAt: String,
    val updatedAt: String,
)

/** The response of a run. */
fun AutomationRun.toResponse(): RunResponse = RunResponse(
    id = id.toString(),
    automationId = automationId.toString(),
    source = source.wire,
    status = status.wire,
    version = version,
    sessionId = sessionId,
    reason = reason,
    problems = problems,
    slotAt = slotAt?.toString(),
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

/** A page of runs. */
@Serializable
data class RunsResponse(val items: List<RunResponse>, val next: String?)

/** The `202` of a webhook. */
@Serializable
data class TriggerResponse(val runId: String, val status: String, val deduplicated: Boolean)

/** A dry-run variable. */
@Serializable
data class DryRunVariableResponse(val name: String, val value: String?, val sensitive: Boolean)

/** The `200` of a dry run. */
@Serializable
data class DryRunResponse(
    val renderedPrompt: String,
    val variables: List<DryRunVariableResponse>,
    val problems: List<PayloadProblem>,
    val wouldSkip: String?,
    val nextRuns: List<String>,
)

/** The response of a dry run. */
fun DryRun.toResponse(): DryRunResponse = DryRunResponse(
    renderedPrompt = renderedPrompt,
    variables = variables.map { DryRunVariableResponse(it.name, it.value, it.sensitive) },
    problems = problems,
    wouldSkip = wouldSkip,
    nextRuns = nextRuns.map { it.toString() },
)
