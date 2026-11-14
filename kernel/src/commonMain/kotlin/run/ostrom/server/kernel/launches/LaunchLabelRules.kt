// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.kernel.launches

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException

/**
 * The rules of a launch's labels, TeamCode's (docs/agent-sessions.md, "Labels and the run identifier", with the model
 * of cloud session labels): at most [MAX] non-secret pairs, a lowercase key of 1 to 63 characters (`[a-z0-9]`, then
 * `[a-z0-9._/-]`), a value of up to 256 characters without control characters. The CLI parses `--label` with
 * [parse], the server checks with [check]: the same rules on both sides.
 */
object LaunchLabelRules {
    /** The most labels a launch carries. */
    const val MAX: Int = 16

    /** The longest value. */
    const val MAX_VALUE: Int = 256

    private val KEY = Regex("[a-z0-9][a-z0-9._/-]{0,62}")

    /**
     * Checks a label set.
     *
     * @param labels The labels.
     *
     * @return The same labels.
     *
     * @throws InvalidLabelException When there are more than [MAX], a key is malformed, or a value is too long or
     *   holds a control character.
     */
    fun check(labels: Map<String, String>): Map<String, String> {
        if (labels.size > MAX) throw InvalidLabelException("labels: at most $MAX.")
        labels.forEach { (key, value) ->
            if (!KEY.matches(key)) {
                throw InvalidLabelException(
                    "label key: 1 to 63 characters, lowercase letters or digits, " +
                        "then letters, digits, '.', '_', '/' or '-'.",
                )
            }
            if (value.length > MAX_VALUE || value.any { it.isISOControl() }) {
                throw InvalidLabelException("label value: up to $MAX_VALUE characters, without control characters.")
            }
        }
        return labels
    }

    /**
     * Reads the `label` filters of a request: each a `key=value` ([parse]), the last value of a repeated key winning,
     * at most [MAX] of them.
     *
     * @param texts The filters.
     *
     * @return The labels a launch must carry.
     *
     * @throws InvalidLabelException When one is malformed or there are more than [MAX].
     */
    fun filter(texts: List<String>): Map<String, String> = check(texts.associate(::parse))

    /**
     * Reads a `key=value` filter: the key is everything before the first `=`, the value may contain `=`.
     *
     * @param text The filter.
     *
     * @return The key and the value.
     *
     * @throws InvalidLabelException When it has no `=`, or its key or value is malformed.
     */
    fun parse(text: String): Pair<String, String> {
        val at = text.indexOf('=')
        if (at < 0) throw InvalidLabelException("label: key=value.")
        val pair = text.substring(0, at) to text.substring(at + 1)
        check(mapOf(pair))
        return pair
    }
}

/**
 * A label of a launch is malformed, or there are too many (`invalid_label`).
 *
 * @param reason Which, and what it must be.
 */
class InvalidLabelException(reason: String) : OstromException(ErrorKind.INVALID_REQUEST, reason, "invalid_label")
