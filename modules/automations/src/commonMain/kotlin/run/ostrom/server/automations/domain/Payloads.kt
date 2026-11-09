// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One step of a payload path: a key of an object or an index of an array. */
sealed interface PathStep {
    /** A key. */
    data class Key(val key: String) : PathStep

    /** An index. */
    data class Index(val index: Int) : PathStep
}

/** Payload paths: `$`, then `.key`, `['key with spaces']` or `[index]` steps, at most 16. */
object PayloadPaths {
    /** The most steps of a path. */
    const val MAX_STEPS = 16

    private val STEP = Regex("\\.([A-Za-z0-9_-]+)|\\['([^']+)']|\\[([0-9]{1,9})]")

    /**
     * Reads a path.
     *
     * @throws InvalidAutomationException When it is malformed or too long.
     */
    fun parse(path: String): List<PathStep> {
        requireAutomation(path.startsWith("$")) { "A variable path starts with $: $path." }
        val steps = mutableListOf<PathStep>()
        var at = 1
        while (at < path.length) {
            val match = STEP.matchAt(path, at) ?: throw InvalidAutomationException("Invalid variable path: $path.")
            val (dotted, quoted, index) = match.destructured
            steps += when {
                dotted.isNotEmpty() -> PathStep.Key(dotted)
                quoted.isNotEmpty() -> PathStep.Key(quoted)
                else -> PathStep.Index(index.toInt())
            }
            at = match.range.last + 1
        }
        requireAutomation(steps.size <= MAX_STEPS) { "A variable path has at most $MAX_STEPS steps: $path." }
        return steps
    }

    /** The element at [steps] in [payload], or null when any step is missing. */
    fun read(payload: JsonElement, steps: List<PathStep>): JsonElement? =
        steps.fold<PathStep, JsonElement?>(payload) { element, step ->
            when (step) {
                is PathStep.Key -> (element as? JsonObject)?.get(step.key)
                is PathStep.Index -> (element as? JsonArray)?.getOrNull(step.index)
            }
        }
}

/**
 * The values of a run, and why a payload was refused.
 *
 * @property values Each variable's text (an optional missing one is empty).
 * @property problems What does not fit; the run is refused when any.
 */
data class ReadPayload(val values: Map<String, String>, val problems: List<PayloadProblem>)

/** Reads payload variables and renders prompts. */
object Prompts {
    /** The variables the server computes for every run. */
    val RUN_VARIABLES = setOf(
        "run.id",
        "run.date",
        "run.time",
        "run.timezone",
        "run.source",
        "automation.id",
        "automation.name",
    )

    private val PLACEHOLDER = Regex("\\{\\{\\s*([^{}]*?)\\s*\\}\\}")

    /** The names a prompt refers to. */
    fun placeholders(prompt: String): Set<String> = PLACEHOLDER.findAll(prompt).map { it.groupValues[1] }.toSet()

    /**
     * Replaces each `{{ name }}` by its value; a value is never scanned again.
     *
     * @param values The values by name; a name without one renders as nothing.
     */
    fun render(prompt: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(prompt) { values[it.groupValues[1]].orEmpty() }

    /**
     * Reads [variables] from [payload].
     *
     * @return The values and the problems.
     */
    fun read(payload: JsonObject, variables: List<AutomationVariable>): ReadPayload {
        val values = mutableMapOf<String, String>()
        val problems = mutableListOf<PayloadProblem>()
        for (variable in variables) {
            val element = PayloadPaths.read(payload, PayloadPaths.parse(variable.path))
            when (val outcome = text(element, variable)) {
                null -> values[variable.name] = ""
                is Outcome.Value -> values[variable.name] = outcome.text
                is Outcome.Problem -> problems += PayloadProblem(variable.path, outcome.rule)
            }
        }
        return ReadPayload(values, problems)
    }

    private sealed interface Outcome {
        data class Value(val text: String) : Outcome

        data class Problem(val rule: String) : Outcome
    }

    /** The text of one value; null for an optional missing one. */
    private fun text(element: JsonElement?, variable: AutomationVariable): Outcome? = when {
        element == null || element is JsonNull -> if (variable.required) Outcome.Problem("required") else null
        element !is JsonPrimitive -> Outcome.Problem("not_scalar")
        else -> checked(typed(element, variable), variable)
    }

    private fun checked(text: String?, variable: AutomationVariable): Outcome = when {
        text == null -> Outcome.Problem("type:${variable.type.wire}")
        text.length > variable.maxLength -> Outcome.Problem("max_length")
        variable.type == VariableType.ENUM && text !in variable.values -> Outcome.Problem("enum")
        else -> Outcome.Value(text)
    }

    private fun typed(element: JsonPrimitive, variable: AutomationVariable): String? = when (variable.type) {
        VariableType.STRING, VariableType.ENUM -> element.content
        VariableType.INT -> element.content.takeIf { !element.isString && it.toLongOrNull() != null }
        VariableType.NUMBER -> element.content.takeIf { !element.isString && it.toDoubleOrNull()?.isFinite() == true }
        VariableType.BOOLEAN -> element.content.takeIf { !element.isString && element.booleanOrNull != null }
    }
}
