// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations.domain

import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** The rules of an automation's definition, checked at every save. */
internal object AutomationRules {
    const val MAX_NAME = 120
    const val MAX_PROMPT = 32_000
    const val MAX_VARIABLES = 32
    const val MAX_ENUM_VALUES = 50
    const val MAX_TEXT = 4096
    const val MAX_GRACE_MINUTES = 1440
    const val MAX_RUNTIME_MINUTES = 1440
    const val MAX_RUNS_PER_DAY = 1000

    /** The largest webhook body, and sample payload. */
    const val MAX_PAYLOAD_BYTES = 64 * 1024

    private val AGENT = Regex("[a-z0-9][a-z0-9-]{0,39}")
    private val MODEL = Regex("[A-Za-z0-9._:/-]{1,100}")
    private val EFFORT = Regex("[a-z]{1,20}")
    private val VARIABLE = Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*")
    /** Names under these prefixes are the ones the run itself provides. */
    private val RESERVED = Regex("^(run|automation)\\.")

    /**
     * Checks a definition.
     *
     * @return The definition, its name trimmed.
     *
     * @throws InvalidAutomationException When a rule is broken.
     */
    fun check(definition: AutomationDefinition): AutomationDefinition {
        val name = definition.name.trim()
        requireAutomation(name.length in 1..MAX_NAME && name.none { it.isISOControl() }) {
            "The name has 1 to $MAX_NAME characters, without control characters."
        }
        requireAutomation(definition.maxRunsPerDay in 1..MAX_RUNS_PER_DAY) {
            "maxRunsPerDay is between 1 and $MAX_RUNS_PER_DAY."
        }
        when (definition.trigger.kind) {
            TriggerKind.CRON -> checkCron(definition)
            TriggerKind.WEBHOOK -> checkWebhook(definition)
        }
        checkAction(definition.action)
        val known = Prompts.RUN_VARIABLES + definition.variables.map { it.name }
        val unknown = Prompts.placeholders(definition.action.prompt) - known
        requireAutomation(unknown.isEmpty()) {
            "The prompt names unknown variables: ${unknown.sorted().joinToString()}."
        }
        return definition.copy(name = name)
    }

    /** The next slot of a cron definition after [after]; null for a webhook or a cron that never runs again. */
    fun nextRun(definition: AutomationDefinition, after: Instant): Instant? {
        val trigger = definition.trigger
        if (trigger.kind != TriggerKind.CRON) return null
        return cronOf(trigger).next(after, zone(trigger.timezone))
    }

    /** The next [count] slots of a cron definition; none for a webhook. */
    fun upcoming(definition: AutomationDefinition, after: Instant, count: Int): List<Instant> {
        val trigger = definition.trigger
        if (trigger.kind != TriggerKind.CRON) return emptyList()
        return cronOf(trigger).upcoming(after, zone(trigger.timezone), count)
    }

    /**
     * An IANA zone.
     *
     * @throws InvalidAutomationException When it is missing or unknown.
     */
    fun zone(id: String?): TimeZone {
        if (id.isNullOrBlank()) throw InvalidAutomationException("A cron has an IANA timezone, such as Europe/Paris.")
        return try {
            TimeZone.of(id)
        } catch (expected: IllegalTimeZoneException) {
            throw InvalidAutomationException("Unknown timezone: $id.")
        }
    }

    private fun cronOf(trigger: AutomationTrigger): CronSchedule =
        CronSchedule.parse(trigger.cron ?: throw InvalidAutomationException("A cron trigger has a cron."))

    private fun checkCron(definition: AutomationDefinition) {
        val trigger = definition.trigger
        cronOf(trigger)
        zone(trigger.timezone)
        requireAutomation(trigger.catchupGraceMinutes in 0..MAX_GRACE_MINUTES) {
            "catchupGraceMinutes is between 0 and $MAX_GRACE_MINUTES."
        }
        requireAutomation(definition.variables.isEmpty() && definition.samplePayload == null) {
            "Payload variables and a sample payload are for webhook triggers."
        }
    }

    private fun checkWebhook(definition: AutomationDefinition) {
        requireAutomation(definition.trigger.cron == null && definition.trigger.timezone == null) {
            "A webhook trigger has no cron nor timezone."
        }
        requireAutomation(definition.variables.size <= MAX_VARIABLES) { "At most $MAX_VARIABLES variables." }
        requireAutomation(definition.variables.map { it.name }.toSet().size == definition.variables.size) {
            "Two variables have the same name."
        }
        definition.variables.forEach(::checkVariable)
        val sample = definition.samplePayload ?: return
        requireAutomation(sample.toString().encodeToByteArray().size <= MAX_PAYLOAD_BYTES) {
            "The sample payload is at most 64 KiB."
        }
        val problems = Prompts.read(sample, definition.variables).problems
        requireAutomation(problems.isEmpty()) {
            "The sample payload does not fit the variables: " +
                problems.joinToString { "${it.path} (${it.rule})" } + "."
        }
    }

    private fun checkVariable(variable: AutomationVariable) {
        requireAutomation(VARIABLE.matches(variable.name) && !RESERVED.containsMatchIn(variable.name)) {
            "Invalid variable name: ${variable.name}."
        }
        PayloadPaths.parse(variable.path)
        requireAutomation(variable.maxLength in 1..MAX_TEXT) { "maxLength is between 1 and $MAX_TEXT." }
        val isEnum = variable.type == VariableType.ENUM
        requireAutomation(if (isEnum) variable.values.size in 1..MAX_ENUM_VALUES else variable.values.isEmpty()) {
            "An enum has 1 to $MAX_ENUM_VALUES values, and only an enum has values."
        }
    }

    private fun checkAction(action: AutomationAction) {
        requireAutomation(action.prompt.isNotBlank() && action.prompt.length <= MAX_PROMPT) {
            "The prompt has 1 to $MAX_PROMPT characters."
        }
        requireAutomation(AGENT.matches(action.agent)) { "Invalid agent." }
        requireAutomation(action.model?.let(MODEL::matches) ?: true) { "Invalid model." }
        requireAutomation(action.effort?.let(EFFORT::matches) ?: true) { "Invalid effort." }
        requireAutomation(action.maxRuntimeMinutes in 1..MAX_RUNTIME_MINUTES) {
            "maxRuntimeMinutes is between 1 and $MAX_RUNTIME_MINUTES."
        }
    }
}
