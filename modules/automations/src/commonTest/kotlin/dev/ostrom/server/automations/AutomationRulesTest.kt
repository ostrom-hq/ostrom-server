// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations

import dev.ostrom.server.automations.domain.AutomationAction
import dev.ostrom.server.automations.domain.AutomationDefinition
import dev.ostrom.server.automations.domain.AutomationRules
import dev.ostrom.server.automations.domain.AutomationTrigger
import dev.ostrom.server.automations.domain.AutomationVariable
import dev.ostrom.server.automations.domain.InvalidAutomationException
import dev.ostrom.server.automations.domain.TriggerKind
import dev.ostrom.server.automations.domain.VariableType
import dev.ostrom.server.automations.fakes.cronDefinition
import dev.ostrom.server.automations.fakes.webhookDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class AutomationRulesTest {
    private val cron = cronDefinition()
    private val webhook = webhookDefinition()

    private fun refused(definition: AutomationDefinition, message: String) {
        val error = assertFailsWith<InvalidAutomationException> { AutomationRules.check(definition) }
        assertEquals(message, error.message)
    }

    @Test
    fun aValidDefinitionIsKeptWithItsNameTrimmed() {
        assertEquals(cron.copy(name = "Nightly"), AutomationRules.check(cron.copy(name = "  Nightly ")))
        val sample = buildJsonObject { putJsonObject("ticket") { put("number", "TKT-1") } }
        assertEquals(webhook.copy(samplePayload = sample), AutomationRules.check(webhook.copy(samplePayload = sample)))
        val full = cron.copy(action = AutomationAction("Go", "codex", "gpt-5.1", "high", 1440), maxRunsPerDay = 1000)
        assertEquals(full, AutomationRules.check(full))
    }

    @Test
    fun theNameTheBudgetAndTheActionAreChecked() {
        val name = "The name has 1 to 120 characters, without control characters."
        refused(cron.copy(name = " "), name)
        refused(cron.copy(name = "x".repeat(121)), name)
        refused(cron.copy(name = "a\nb"), name)
        refused(cron.copy(maxRunsPerDay = 0), "maxRunsPerDay is between 1 and 1000.")
        refused(cron.copy(maxRunsPerDay = 1001), "maxRunsPerDay is between 1 and 1000.")
        fun action(action: AutomationAction) = cron.copy(action = action)
        refused(action(AutomationAction(" ")), "The prompt has 1 to 32000 characters.")
        refused(action(AutomationAction("x".repeat(32_001))), "The prompt has 1 to 32000 characters.")
        refused(action(AutomationAction("x", agent = "Claude")), "Invalid agent.")
        refused(action(AutomationAction("x", model = "a b")), "Invalid model.")
        refused(action(AutomationAction("x", effort = "HIGH")), "Invalid effort.")
        refused(action(AutomationAction("x", maxRuntimeMinutes = 0)), "maxRuntimeMinutes is between 1 and 1440.")
        refused(action(AutomationAction("x", maxRuntimeMinutes = 1441)), "maxRuntimeMinutes is between 1 and 1440.")
        val unknown = "The prompt names unknown variables: run.nope, ticket."
        refused(action(AutomationAction("{{ticket}} {{run.nope}}")), unknown)
    }

    @Test
    fun aCronHasAScheduleAZoneAGraceAndNoPayload() {
        fun trigger(trigger: AutomationTrigger) = cron.copy(trigger = trigger)
        refused(trigger(AutomationTrigger(TriggerKind.CRON, timezone = "UTC")), "A cron trigger has a cron.")
        val zone = "A cron has an IANA timezone, such as Europe/Paris."
        refused(trigger(AutomationTrigger(TriggerKind.CRON, "0 0 * * *")), zone)
        refused(trigger(AutomationTrigger(TriggerKind.CRON, "0 0 * * *", " ")), zone)
        val mars = AutomationTrigger(TriggerKind.CRON, "0 0 * * *", "Mars/Olympus")
        refused(trigger(mars), "Unknown timezone: Mars/Olympus.")
        val grace = "catchupGraceMinutes is between 0 and 1440."
        refused(trigger(AutomationTrigger(TriggerKind.CRON, "0 0 * * *", "UTC", catchupGraceMinutes = -1)), grace)
        refused(trigger(AutomationTrigger(TriggerKind.CRON, "0 0 * * *", "UTC", catchupGraceMinutes = 1441)), grace)
        val payload = "Payload variables and a sample payload are for webhook triggers."
        refused(cron.copy(variables = listOf(AutomationVariable("a", "$.a"))), payload)
        refused(cron.copy(samplePayload = buildJsonObject {}), payload)
    }

    @Test
    fun aWebhookHasDistinctValidVariablesAndAFittingSample() {
        refused(
            webhook.copy(trigger = AutomationTrigger(TriggerKind.WEBHOOK, cron = "0 0 * * *")),
            "A webhook trigger has no cron nor timezone.",
        )
        refused(
            webhook.copy(trigger = AutomationTrigger(TriggerKind.WEBHOOK, timezone = "UTC")),
            "A webhook trigger has no cron nor timezone.",
        )
        refused(webhook.copy(variables = List(33) { AutomationVariable("v$it", "$.v") }), "At most 32 variables.")
        refused(webhook.copy(variables = webhook.variables + webhook.variables[0]), "Two variables have the same name.")
        fun variable(variable: AutomationVariable) = webhook.copy(variables = webhook.variables + variable)
        refused(variable(AutomationVariable("run.x", "$.x")), "Invalid variable name: run.x.")
        refused(variable(AutomationVariable("automation.x", "$.x")), "Invalid variable name: automation.x.")
        refused(variable(AutomationVariable("a..b", "$.x")), "Invalid variable name: a..b.")
        refused(variable(AutomationVariable("a", "x")), "A variable path starts with $: x.")
        refused(variable(AutomationVariable("a", "$.x", maxLength = 0)), "maxLength is between 1 and 4096.")
        refused(variable(AutomationVariable("a", "$.x", maxLength = 4097)), "maxLength is between 1 and 4096.")
        val enum = "An enum has 1 to 50 values, and only an enum has values."
        refused(variable(AutomationVariable("a", "$.x", VariableType.ENUM)), enum)
        refused(variable(AutomationVariable("a", "$.x", VariableType.ENUM, List(51) { "v$it" })), enum)
        refused(variable(AutomationVariable("a", "$.x", VariableType.STRING, listOf("v"))), enum)
        val choice = variable(AutomationVariable("a", "$.x", VariableType.ENUM, List(50) { "v$it" }))
        assertEquals(choice, AutomationRules.check(choice))
        refused(
            webhook.copy(samplePayload = buildJsonObject { put("x", "y".repeat(64 * 1024)) }),
            "The sample payload is at most 64 KiB.",
        )
        refused(
            webhook.copy(samplePayload = buildJsonObject { put("ticket", JsonPrimitive(1)) }),
            "The sample payload does not fit the variables: $.ticket.number (required).",
        )
    }

    @Test
    fun theNextSlotsOfACronAndNoneForAWebhook() {
        val after = Instant.parse("2026-10-30T10:00:00Z")
        assertEquals(Instant.parse("2026-10-30T20:00:00Z"), AutomationRules.nextRun(cron, after))
        assertEquals(3, AutomationRules.upcoming(cron, after, 3).size)
        assertNull(AutomationRules.nextRun(webhook, after))
        assertEquals(emptyList(), AutomationRules.upcoming(webhook, after, 3))
    }
}
