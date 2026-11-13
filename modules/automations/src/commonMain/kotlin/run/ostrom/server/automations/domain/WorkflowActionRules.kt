// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.automations.domain

/** The rules of a workflow action (TC-AUT-5), refused `invalid_automation` naming the field. */
internal object WorkflowActionRules {
    /** The most inputs a workflow declares, so the most a workflow action gives. */
    private const val MAX_INPUTS = 16
    private const val MAX_ID = 64

    private val WORKFLOW_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val INPUT_ID = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val REPOSITORY = Regex("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")

    /**
     * Checks a workflow action on its own: no prompt, a workflow id, inputs of valid ids whose templates name known
     * variables, and a repository `owner/name`; the agent, model, effort and runtime are not used.
     *
     * @param action The action.
     * @param workflowId Its workflow.
     * @param known The variables its templates may name.
     */
    fun check(action: AutomationAction, workflowId: String, known: Set<String>) {
        requireWorkflow(action.prompt.isEmpty()) { "action.prompt: a workflow action has an empty prompt." }
        requireWorkflow(WORKFLOW_ID.matches(workflowId)) { "action.workflowId: invalid id." }
        requireWorkflow(action.inputs.size <= MAX_INPUTS) { "action.inputs: at most $MAX_INPUTS inputs." }
        for ((id, template) in action.inputs.entries.sortedBy { it.key }) {
            requireWorkflow(INPUT_ID.matches(id)) { "action.inputs.${id.take(MAX_ID)}: invalid input id." }
            requireWorkflow(template.length <= AutomationRules.MAX_TEXT) {
                "action.inputs.$id: at most ${AutomationRules.MAX_TEXT} characters."
            }
            val unknown = Prompts.placeholders(template) - known
            requireWorkflow(unknown.isEmpty()) {
                "action.inputs.$id: names unknown variables: ${unknown.sorted().joinToString()}."
            }
        }
        requireWorkflow(action.repository?.let(REPOSITORY::matches) ?: true) {
            "action.repository: owner/name."
        }
    }

    /**
     * Checks a workflow action against its workflow's inputs (TeamCode `AutomationService`): every given input is
     * declared, and every required one without a default is given.
     *
     * @param action The action, its own rules already checked.
     * @param declared The workflow's inputs, or null when it does not exist (or is only a proposal).
     *
     * @throws InvalidAutomationException `invalid_automation`, naming the workflow or the input.
     */
    fun checkInputs(action: AutomationAction, declared: List<WorkflowInputSpec>?) {
        val inputs = declared
            ?: throw invalidWorkflowAction("action.workflowId: no workflow ${action.workflowId} that a run may start.")
        val ids = inputs.map { it.id }.toSet()
        for (id in action.inputs.keys.sorted()) {
            requireWorkflow(id in ids) { "action.inputs.$id: not an input of the workflow." }
        }
        for (input in inputs) {
            requireWorkflow(!input.required || input.hasDefault || !action.inputs[input.id].isNullOrBlank()) {
                "action.inputs.${input.id}: required by the workflow, which has no default."
            }
        }
    }
}
