// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay

import dev.ostrom.server.mcprelay.domain.ArgumentCondition
import dev.ostrom.server.mcprelay.domain.ArgumentPolicy
import dev.ostrom.server.mcprelay.domain.ConditionOperator
import dev.ostrom.server.mcprelay.domain.InvalidMcpServerException
import dev.ostrom.server.mcprelay.domain.McpRules
import dev.ostrom.server.mcprelay.domain.PolicyEffect
import dev.ostrom.server.mcprelay.domain.SafePattern
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The `matches` operator runs an admin's pattern on an agent's arguments: no catastrophic backtracking. */
class SafePatternTest {
    @Test
    fun patternsWithoutNestedRepetitionAreAccepted() {
        val safe = listOf(
            "^prod",
            "drop\\s+table",
            "(prod|staging)-db",
            "[a-z]+\\.[a-z]{2,}",
            "(a+)?",
            "(abc)+",
            "\\(a+\\)+",
            "[(a+)]+",
            "a{2,3}b*",
            "a*(b)c",
            "[^]a]+",
            "[\\]]+",
            "[abc",
            "a)b",
            "a\\",
            "\\d+",
        )
        for (pattern in safe) assertTrue(SafePattern.isSafe(pattern), pattern)
    }

    @Test
    fun nestedRepetitionAndBackreferencesAreRefused() {
        val unsafe = listOf(
            "(a+)+$",
            "(a*)*",
            "((ab)*c)+",
            "(x+x+)+y",
            "(a{1,5}){2,}",
            "(?:a|b+)*",
            "(a)\\1",
            "(?<x>a)\\k<x>",
        )
        for (pattern in unsafe) assertFalse(SafePattern.isSafe(pattern), pattern)
    }

    @Test
    fun aPolicyWithAnUnsafePatternIsRefused() {
        val policy = ArgumentPolicy(
            id = Uuid.random(),
            orgId = Uuid.random(),
            serverId = Uuid.random(),
            tool = "query",
            conditions = listOf(ArgumentCondition("q", ConditionOperator.MATCHES, "(a+)+$")),
            effect = PolicyEffect.DENY,
            message = "No.",
            createdAt = Instant.parse("2026-10-30T04:30:00Z"),
        )
        val refused = assertFailsWith<InvalidMcpServerException> { McpRules.requireArgumentPolicy(policy) }
        assertEquals(
            "A pattern may not repeat a repeated group nor refer back to a group (it could run for minutes).",
            refused.message,
        )
    }

    @Test
    fun anArgumentTooLongToMatchCountsAsMatching() {
        val condition = ArgumentCondition("q", ConditionOperator.MATCHES, "^select")
        val long = "x".repeat(ArgumentCondition.MAX_MATCHED_LENGTH + 1)
        assertTrue(
            condition.holds(buildJsonObject { put("q", JsonPrimitive(long)) }),
            "Fail closed: a deny still applies to what it cannot read",
        )
        assertFalse(condition.holds(buildJsonObject { put("q", JsonPrimitive("x".repeat(100))) }))
    }
}
