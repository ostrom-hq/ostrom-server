// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay

import dev.ostrom.server.mcprelay.domain.ArgumentCondition
import dev.ostrom.server.mcprelay.domain.ArgumentPolicy
import dev.ostrom.server.mcprelay.domain.ConditionOperator
import dev.ostrom.server.mcprelay.domain.ConditionOperator.ABSENT
import dev.ostrom.server.mcprelay.domain.ConditionOperator.CONTAINS
import dev.ostrom.server.mcprelay.domain.ConditionOperator.EQUALS
import dev.ostrom.server.mcprelay.domain.ConditionOperator.EXISTS
import dev.ostrom.server.mcprelay.domain.ConditionOperator.MATCHES
import dev.ostrom.server.mcprelay.domain.ConditionOperator.NOT_EQUALS
import dev.ostrom.server.mcprelay.domain.ConditionOperator.PREFIX
import dev.ostrom.server.mcprelay.domain.PolicyEffect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Conditions read a dotted path into the arguments and compare what they find. */
class ArgumentPolicyTest {
    private val arguments: JsonObject = Json.parseToJsonElement(
        """{"db":"prod-eu","query":"DROP table users","limit":10,"none":null,""" +
            """"options":{"schema":"public","flags":{"a":1}},"items":[{"id":"x1"}]}""",
    ).jsonObject

    private fun holds(path: String, operator: ConditionOperator, value: String? = null) =
        ArgumentCondition(path, operator, value).holds(arguments)

    @Test
    fun aPathWalksObjectsAndArraysAndStopsAtAnythingElse() {
        assertEquals(true, holds("options.schema", EQUALS, "public"))
        assertEquals(true, holds("items.0.id", EQUALS, "x1"))
        assertEquals(false, holds("items.first.id", EXISTS))
        assertEquals(false, holds("items.5", EXISTS))
        assertEquals(false, holds("db.inner", EXISTS))
        assertEquals(true, holds("options.flags", EQUALS, """{"a":1}"""), "An object compares as its JSON")
        assertEquals(true, holds("limit", EQUALS, "10"))
    }

    @Test
    fun eachOperatorComparesWhatItFinds() {
        val cases = listOf(
            Triple(holds("db", EXISTS), holds("none", EXISTS), holds("missing", EXISTS)) to listOf(true, false, false),
            Triple(holds("missing", ABSENT), holds("none", ABSENT), holds("db", ABSENT)) to listOf(true, true, false),
            Triple(holds("db", EQUALS, "prod-eu"), holds("db", NOT_EQUALS, "prod-eu"), holds("db", NOT_EQUALS, "x")) to
                listOf(true, false, true),
            Triple(holds("db", PREFIX, "prod"), holds("db", PREFIX, "dev"), holds("missing", PREFIX, "p")) to
                listOf(true, false, false),
            Triple(
                holds("query", CONTAINS, "drop"),
                holds("query", CONTAINS, "insert"),
                holds("none", CONTAINS, "x"),
            ) to
                listOf(true, false, false),
            Triple(
                holds("query", MATCHES, "^drop\\s"),
                holds("query", MATCHES, "^insert"),
                holds("none", MATCHES, "."),
            ) to
                listOf(true, false, false),
        )
        for ((results, expected) in cases) {
            assertEquals(expected, results.toList())
        }
    }

    private fun policy(tool: String, effect: PolicyEffect, second: Long, vararg conditions: ArgumentCondition) =
        ArgumentPolicy(
            Uuid.random(),
            Relay.orgA,
            Uuid.random(),
            tool,
            conditions.toList(),
            effect,
            "No.",
            Relay.epoch + second.seconds,
        )

    @Test
    fun aRefusalWinsOverAnApprovalAndTheOldestFirst() {
        val approval = policy(ArgumentPolicy.ANY_TOOL, PolicyEffect.REQUIRE_APPROVAL, 0)
        val newerDeny = policy("query", PolicyEffect.DENY, 2)
        val olderDeny = policy("query", PolicyEffect.DENY, 1, ArgumentCondition("db", PREFIX, "prod"))
        val otherTool = policy("update", PolicyEffect.DENY, 0)
        val notHolding = policy("query", PolicyEffect.DENY, 0, ArgumentCondition("db", EQUALS, "dev"))
        val all = listOf(approval, newerDeny, olderDeny, otherTool, notHolding)

        assertEquals(olderDeny, ArgumentPolicy.decide(all, "query", arguments))
        assertEquals(approval, ArgumentPolicy.decide(all, "list", arguments))
        assertNull(ArgumentPolicy.decide(listOf(otherTool, notHolding), "query", arguments))
    }
}
