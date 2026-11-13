// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules.domain.services

import run.ostrom.server.rules.domain.models.GatedRule
import run.ostrom.server.rules.domain.models.ProcessCommand
import run.ostrom.server.rules.domain.models.ProcessNode
import run.ostrom.server.rules.domain.models.ProcessNodeKind
import run.ostrom.server.rules.domain.models.ProcessProposal
import run.ostrom.server.rules.domain.models.RuleSource
import run.ostrom.server.rules.domain.services.RepositoryContext.Companion.shown

// The nodes, names and bounds of a proposed process (ProcessInference).

// The kinds a proposal never cuts away.
private val bounded = setOf(ProcessNodeKind.TRIGGER, ProcessNodeKind.GATE, ProcessNodeKind.NOTIFY)

// The rules of the same analysis that gate this boundary in this repository, one gate node each.
internal fun processGates(
    context: RepositoryContext,
    gated: List<GatedRule>,
    classes: Set<String>,
    label: String,
): List<ProcessNode> = gated.filter { rule ->
    rule.gate.actions.any { it.cls in classes && (it.repos.isEmpty() || context.name in it.repos) }
}.take(ProcessInference.MAX_GATES).map { ProcessNode("", ProcessNodeKind.GATE, label, rule = it) }

// A job's commands, joined while they fit a suggestion.
internal fun joinedCommand(job: CiJob): ProcessCommand? {
    val first = job.commands.firstOrNull() ?: return null
    var text = first.second
    for ((_, next) in job.commands.drop(1)) {
        val longer = "$text && $next"
        if (longer.length > ProcessCommands.MAX_COMMAND) break
        text = longer
    }
    return ProcessCommand(text, lineEvidence(job.path, first.first))
}

internal fun lineEvidence(path: String, line: Int): String = "${RepositoryContext.clean(path)}:$line"

internal fun targetEvidence(target: ReleaseTarget): String =
    RepositoryContext.clean(target.path) + target.line?.let { ":$it" }.orEmpty()

internal fun processNode(kind: ProcessNodeKind, label: String, command: ProcessCommand? = null) =
    ProcessNode("", kind, label, command)

// A name read from the repository, [shown], with where it was found.
internal fun RepositoryContext.named(title: String, where: String = name): String = shown("${shown(title)} ($where)")

// The trigger, gates and notifications are kept; the steps and checks fill what is left of MAX_NODES. Each node is
// then numbered in order.
internal fun RepositoryContext.proposal(
    kind: String,
    name: String,
    description: String?,
    nodes: List<ProcessNode>,
    sources: List<RuleSource>,
): ProcessProposal {
    var left = ProcessInference.MAX_NODES - nodes.count { it.kind in bounded }
    val kept = nodes.filter { it.kind in bounded || left-- > 0 }.mapIndexed { index, node ->
        node.copy(id = "n${index + 1}")
    }
    return ProcessProposal(
        key = "process:${this.name}:$kind",
        name = name,
        description = description,
        repo = "${snapshot.repo.owner}/${this.name}",
        nodes = kept,
        sources = sources.distinct().take(ProcessInference.MAX_SOURCES),
    )
}
