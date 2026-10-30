// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What a matching argument policy does to a call. */
enum class PolicyEffect {
    /** The call is refused. */
    DENY,

    /** The call is held until a human approves it (a gate); the agent is told so. */
    REQUIRE_APPROVAL,
}

/** How a condition compares an argument. */
enum class ConditionOperator { EQUALS, NOT_EQUALS, MATCHES, PREFIX, CONTAINS, EXISTS, ABSENT }

/**
 * One test on a call's arguments.
 *
 * @property path A dotted path into the arguments (`query`, `options.database`, `items.0.id`).
 * @property operator The comparison.
 * @property value The operand; a regular expression for [ConditionOperator.MATCHES] (case-insensitive), unused for
 *   [ConditionOperator.EXISTS] and [ConditionOperator.ABSENT].
 */
data class ArgumentCondition(val path: String, val operator: ConditionOperator, val value: String? = null) {
    private val regex: Regex? = if (operator ==
        ConditionOperator.MATCHES
    ) {
        Regex(requireNotNull(value), RegexOption.IGNORE_CASE)
    } else {
        null
    }

    /**
     * Tests the condition.
     *
     * @param arguments The call's arguments.
     *
     * @return Whether it holds.
     */
    fun holds(arguments: JsonObject): Boolean {
        val found = arguments.at(path)
        val text = found?.asText()
        return when (operator) {
            ConditionOperator.EXISTS -> found != null && found != JsonNull
            ConditionOperator.ABSENT -> found == null || found == JsonNull
            ConditionOperator.EQUALS -> text == value
            ConditionOperator.NOT_EQUALS -> text != value
            ConditionOperator.PREFIX -> text != null && text.startsWith(requireNotNull(value))
            ConditionOperator.CONTAINS -> text != null && text.contains(requireNotNull(value), ignoreCase = true)
            // Text too long to match in bounded time counts as matching: a deny still applies to what it cannot read.
            ConditionOperator.MATCHES ->
                text != null &&
                    (text.length > MAX_MATCHED_LENGTH || requireNotNull(regex).containsMatchIn(text))
        }
    }

    private fun JsonObject.at(path: String): JsonElement? =
        path.split('.').fold<String, JsonElement?>(this) { current, segment ->
            when (current) {
                is JsonObject -> current[segment]
                is JsonArray -> segment.toIntOrNull()?.let { current.getOrNull(it) }
                else -> null
            }
        }

    private fun JsonElement.asText(): String? = when (this) {
        is JsonNull -> null
        is JsonPrimitive -> content
        else -> toString()
    }

    /** The bounds of a condition. */
    companion object {
        /** The longest argument a `matches` pattern reads; a longer one counts as matching (fail closed). */
        const val MAX_MATCHED_LENGTH: Int = 8192
    }
}

/**
 * A rule on the arguments of relayed tool calls (workflow extension 1: a check sees the action, tool and
 * arguments, and the relay guards relayed MCP tools). It applies when every condition holds.
 *
 * @property id The id.
 * @property orgId The org.
 * @property serverId The server.
 * @property tool The tool it watches, or `*` for every tool of the server.
 * @property conditions The tests, all required; none means every call of the tool.
 * @property effect What happens to a matching call.
 * @property message Why, shown to the agent and the approver.
 * @property createdAt When it was created.
 */
data class ArgumentPolicy(
    val id: Uuid,
    val orgId: Uuid,
    val serverId: Uuid,
    val tool: String,
    val conditions: List<ArgumentCondition>,
    val effect: PolicyEffect,
    val message: String,
    val createdAt: Instant,
) {
    /**
     * Whether the policy applies to a call.
     *
     * @param toolName The called tool.
     * @param arguments Its arguments.
     */
    fun matches(toolName: String, arguments: JsonObject): Boolean =
        (tool == ANY_TOOL || tool == toolName) && conditions.all { it.holds(arguments) }

    /** Matching and deciding. */
    companion object {
        /** The tool name that matches every tool. */
        const val ANY_TOOL: String = "*"

        /**
         * The policy that decides a call: a refusal wins over an approval, the oldest first.
         *
         * @param policies The server's policies.
         * @param toolName The called tool.
         * @param arguments Its arguments.
         *
         * @return The deciding policy, or null when the call is allowed.
         */
        fun decide(policies: List<ArgumentPolicy>, toolName: String, arguments: JsonObject): ArgumentPolicy? =
            policies.filter { it.matches(toolName, arguments) }
                .sortedWith(compareBy<ArgumentPolicy> { it.effect != PolicyEffect.DENY }.thenBy { it.createdAt })
                .firstOrNull()
    }
}
