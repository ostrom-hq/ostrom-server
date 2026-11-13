// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules.domain.models

/**
 * The kinds of node a process the analysis finds may hold: those compiled for a session, never those the server runs
 * (`agent`, `wait`, `workflow`), which only a person adds.
 *
 * @property value The contract's name (`WorkflowNodeKind`).
 */
enum class ProcessNodeKind(val value: String) {
    TRIGGER("trigger"),
    STEP("step"),
    CHECK("check"),
    GATE("gate"),
    NOTIFY("notify"),
}

/**
 * A command the analysis suggests for a node, never one it runs: a person takes it or not (`suggested.command`).
 *
 * @property command The command, as the repository runs it, with nothing that looked like a secret.
 * @property evidence Where it was read (`api/.github/workflows/ci.yml:12`).
 */
data class ProcessCommand(val command: String, val evidence: String)

/**
 * A rule found in the same analysis that gates a boundary a process passes (a merge, a release).
 *
 * @property id The rule's id.
 * @property gate Its gate: the process's gate node carries its actions and approvers.
 */
data class GatedRule(val id: String, val gate: RuleGate)

/**
 * One node of a proposed process.
 *
 * @property id Its id in the workflow.
 * @property kind What it is.
 * @property label What it shows: words of the analysis, or a name read from the repository, cleaned and cut.
 * @property command The command it suggests, if any.
 * @property rule The rule whose gate a gate node is.
 */
data class ProcessNode(
    val id: String,
    val kind: ProcessNodeKind,
    val label: String,
    val command: ProcessCommand? = null,
    val rule: GatedRule? = null,
)

/**
 * A process the analysis proposes as a workflow, before it is stored. It is stored `proposed` and never accepted, run
 * or rewritten by an analysis: a person reads it in the console, then accepts or rejects it.
 *
 * @property key What it was found from (`process:api:pull-request`): the same finding in a later run gives the same
 *   key, so its id never changes and it is never proposed twice.
 * @property name Its name.
 * @property description What the analysis counted, in its own words.
 * @property repo The repository it was found in, as `owner/name` (the workflow's `repos`).
 * @property nodes Its nodes, in order: each leads to the next, the last ends it.
 * @property sources The files and lines it was read from.
 */
data class ProcessProposal(
    val key: String,
    val name: String,
    val description: String?,
    val repo: String,
    val nodes: List<ProcessNode>,
    val sources: List<RuleSource>,
)
