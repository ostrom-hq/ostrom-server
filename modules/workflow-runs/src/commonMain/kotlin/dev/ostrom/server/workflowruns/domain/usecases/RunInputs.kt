// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns.domain.usecases

import dev.ostrom.core.api.models.WorkflowInput
import dev.ostrom.server.workflowruns.domain.models.InvalidRunException

/** The inputs a run starts with: only those its workflow declares, each within its bounds. */
object RunInputs {
    /**
     * Checks the given inputs against the declared ones and fills the defaults.
     *
     * @param declared The inputs the workflow declares.
     * @param given The inputs of the request.
     *
     * @return The run's inputs, in the declared order: a blank or absent one takes its default, and one without a
     *   value is left out.
     *
     * @throws InvalidRunException For an input the workflow does not declare, a required one without a value, or a
     *   value longer than its `maxLength`.
     */
    fun of(declared: List<WorkflowInput>, given: Map<String, String>): Map<String, String> {
        val ids = declared.map { it.id }.toSet()
        given.keys.firstOrNull { it !in ids }?.let {
            throw InvalidRunException("inputs.${it.take(RunTexts.ID)}: not an input of this workflow.")
        }
        val values = LinkedHashMap<String, String>()
        for (input in declared) {
            val value = given[input.id]?.takeIf { it.isNotBlank() } ?: input.default
            if (value.isNullOrBlank()) {
                if (input.required) throw InvalidRunException("inputs.${input.id}: required.")
            } else if (value.length > input.maxLength) {
                throw InvalidRunException("inputs.${input.id}: at most ${input.maxLength} characters.")
            } else {
                values[input.id] = value
            }
        }
        return values
    }
}
