// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules.domain.services

import run.ostrom.server.rules.domain.models.GatedRule
import run.ostrom.server.rules.domain.models.ProcessCommand
import run.ostrom.server.rules.domain.models.ProcessNode
import run.ostrom.server.rules.domain.models.ProcessNodeKind
import run.ostrom.server.rules.domain.models.ProcessProposal
import run.ostrom.server.rules.domain.models.RepositorySnapshot
import run.ostrom.server.rules.domain.models.RuleSource
import run.ostrom.server.rules.domain.services.RepositoryContext.Companion.counted
import run.ostrom.server.rules.domain.services.RepositoryContext.Companion.shown

/**
 * The processes a repository's files state, proposed as workflows without a model (decision "one workflow system",
 * section 4): its pull request checks (the CI jobs run on pull requests, the template's checklist), its release (the
 * CI jobs run on tags or releases, its release scripts and targets), each numbered list under a heading of its agent
 * instructions and `CONTRIBUTING.md`, and each Claude Code skill or command.
 *
 * Everything a repository holds is untrusted: every name is [shown] (one clean line, at most 120 characters), a
 * command is only a suggestion and only when [ProcessCommands.safe] keeps it, the nodes are only `trigger`, `step`,
 * `check`, `gate` and `notify`, and every count is bounded. The same files always give the same proposals.
 */
object ProcessInference {
    /** The nodes one proposal holds at most. */
    const val MAX_NODES: Int = 20

    /** The proposals one repository gives at most. */
    const val MAX_PER_REPO: Int = 20

    /** The proposals one analysis stores at most. */
    const val MAX_PROPOSALS: Int = 50

    /** The characters of one file read at most; past them, the file is cut at a line. */
    const val MAX_CHARS: Int = 64 * 1024

    /** The CI files of one repository read at most. */
    const val MAX_CI_FILES: Int = 10

    /** The skills and commands of one repository read at most. */
    const val MAX_SKILLS: Int = 10

    /** The sources one proposal lists at most. */
    const val MAX_SOURCES: Int = 20

    /** The rule gates one gate boundary names at most. */
    const val MAX_GATES: Int = 3

    /** The agent instructions and contribution guides whose numbered lists are processes. */
    val instructionFiles: List<String> = listOf(
        "CLAUDE.md", "AGENTS.md", ".github/copilot-instructions.md", "CONTRIBUTING.md", ".github/CONTRIBUTING.md",
        "docs/CONTRIBUTING.md",
    )

    /** Where GitHub reads the pull request template, in its order; the name is matched in any case. */
    val templates: List<String> = listOf(
        ".github/pull_request_template.md", "pull_request_template.md", "docs/pull_request_template.md",
    )

    private const val MANUAL_RELEASE = "Someone starts a release"
    private const val TAGGED_RELEASE = "A tag is pushed or a release is published"
    private const val CI = "ci"
    private const val DOC = "doc"
    private const val CONFIG = "config"
    private val mergeClasses = setOf("merge", "push-main")
    private val releaseClasses = setOf("push-tag", "deploy")
    private val bounded = setOf(ProcessNodeKind.TRIGGER, ProcessNodeKind.GATE, ProcessNodeKind.NOTIFY)
    private val skill = Regex("^\\.claude/skills/([^/]+)/SKILL\\.md$")
    private val command = Regex("^\\.claude/commands/([^/]+)\\.md$")
    private val testCommand = Regex("\\b(test|check|verify)\\b")

    /**
     * The files the processes are read from, bounded: the CI files, the release manifests, the agent instructions,
     * the pull request template, the skills and commands.
     *
     * @param paths The repository's files.
     * @return Their paths.
     */
    fun wantedFiles(paths: List<String>): List<String> {
        val all = paths.toSet()
        val ci = paths.filter(CiFacts::isCiFile).sorted().take(MAX_CI_FILES)
        val fixed = (listOf(ReleaseFacts.MAKEFILE, RepositoryFacts.PACKAGE_JSON, ReleaseFacts.FASTFILE) +
            instructionFiles).filter { it in all }
        val skills = paths.filter { skill.matches(it) || command.matches(it) }.sorted().take(MAX_SKILLS)
        return ci + fixed + listOfNotNull(template(paths)) + skills
    }

    // The template GitHub applies: the first of its places, the name in any case.
    private fun template(paths: List<String>): String? =
        templates.firstNotNullOfOrNull { place -> paths.firstOrNull { it.lowercase() == place } }

    /**
     * Cuts a file at [MAX_CHARS], at the last line that fits.
     *
     * @param text The file.
     * @return The text read.
     */
    fun bounded(text: String): String =
        if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS).substringBeforeLast('\n')

    /**
     * Proposes the processes of one repository.
     *
     * @param snapshot What the analysis read of it.
     * @param gated The rules of the same analysis that put a gate.
     * @return Its proposals, at most [MAX_PER_REPO]: pull request checks, release, instruction lists, skills.
     */
    fun infer(snapshot: RepositorySnapshot, gated: List<GatedRule>): List<ProcessProposal> {
        val context = RepositoryContext(snapshot)
        val files = snapshot.files.mapValues { bounded(it.value) }
        val ci = files.keys.filter(CiFacts::isCiFile).sorted().map { CiFacts.read(it, files.getValue(it)) }
        val jobs = ci.flatMap { it.jobs }
        val seen = jobs.flatMap { job -> job.commands.map { (line, text) -> text to evidence(job.path, line) } }
            .distinctBy { it.first }.toMap()
        val proposals = listOfNotNull(
            pullRequest(context, jobs, files, gated),
            release(context, jobs, ci.flatMap { it.notices }, files, gated),
        ) + instructions(context, files, seen) + skills(context, files)
        return proposals.take(MAX_PER_REPO)
    }

    private fun pullRequest(
        context: RepositoryContext,
        jobs: List<CiJob>,
        files: Map<String, String>,
        gated: List<GatedRule>,
    ): ProcessProposal? {
        val checks = jobs.filter { it.event == CiEvent.PULL_REQUEST }
        val template = template(files.keys.toList())?.let { it to MarkdownFacts.checklist(files.getValue(it)) }
        val items = template?.second.orEmpty()
        if (checks.isEmpty() && items.isEmpty()) return null
        val nodes = listOf(node(ProcessNodeKind.TRIGGER, "A pull request is opened or updated")) +
            items.map { node(ProcessNodeKind.STEP, shown(MarkdownFacts.words(it.text))) } +
            checks.map { node(ProcessNodeKind.CHECK, shown(it.name), joined(it)) } +
            gates(context, gated, mergeClasses, "A yes before merging")
        val sources = template?.let { (path, listed) -> listed.map { context.line(path, it.line, DOC) } }.orEmpty() +
            checks.map { context.line(it.path, it.line, CI) }
        val description = listOfNotNull(
            "${counted(checks.size, "CI job")} on each pull request.".takeIf { checks.isNotEmpty() },
            "The pull request template lists ${counted(items.size, "item")}.".takeIf { items.isNotEmpty() },
        ).joinToString(" ")
        return proposal(context, "pull-request", "Pull request checks", description, nodes, sources)
    }

    private fun release(
        context: RepositoryContext,
        jobs: List<CiJob>,
        notices: List<CiNotice>,
        files: Map<String, String>,
        gated: List<GatedRule>,
    ): ProcessProposal? {
        val released = jobs.filter { it.event == CiEvent.RELEASE }
        val paths = context.snapshot.tree.paths
        val targets = ReleaseFacts.scripts(paths) +
            files[ReleaseFacts.MAKEFILE]?.let(ReleaseFacts::makefile).orEmpty() +
            files[RepositoryFacts.PACKAGE_JSON]?.let { text ->
                ReleaseFacts.packageJson(text) { BuildFacts.runScript(paths, it) }
            }.orEmpty() +
            files[ReleaseFacts.FASTFILE]?.let(ReleaseFacts::fastfile).orEmpty()
        if (released.isEmpty() && targets.isEmpty()) return null
        val tested = jobs.filter { it.event == CiEvent.PULL_REQUEST }.firstNotNullOfOrNull { job ->
            job.commands.firstOrNull { testCommand.containsMatchIn(it.second) }
                ?.let { ProcessCommand(it.second, evidence(job.path, it.first)) }
        }
        val trigger = if (released.isEmpty()) MANUAL_RELEASE else TAGGED_RELEASE
        val nodes = listOf(node(ProcessNodeKind.TRIGGER, trigger)) +
            listOfNotNull(tested?.let { node(ProcessNodeKind.CHECK, "The tests pass", it) }) +
            gates(context, gated, releaseClasses, "A yes before releasing") +
            released.map { node(ProcessNodeKind.STEP, shown(it.name), joined(it)) } +
            targets.map { node(ProcessNodeKind.STEP, shown(it.command), ProcessCommand(it.command, target(it))) } +
            notices.map { node(ProcessNodeKind.NOTIFY, "Tell the team on ${it.service}") }
        val sources = released.map { context.line(it.path, it.line, CI) } +
            targets.map { context.line(it.path, it.line, CONFIG) } +
            notices.map { context.line(it.path, it.line, CI) }
        val description = listOfNotNull(
            "${counted(released.size, "CI job")} on tags or releases.".takeIf { released.isNotEmpty() },
            "${counted(targets.size, "release target")} found.".takeIf { targets.isNotEmpty() },
        ).joinToString(" ")
        return proposal(context, "release", "Release", description, nodes, sources)
    }

    private fun instructions(
        context: RepositoryContext,
        files: Map<String, String>,
        seen: Map<String, String>,
    ): List<ProcessProposal> = instructionFiles.filter { it in files }.flatMap { path ->
        MarkdownFacts.lists(files.getValue(path)).map { list ->
            val nodes = list.items.map { item ->
                val label = shown(MarkdownFacts.words(item.text))
                val run = MarkdownFacts.codeSpans(item.text).firstNotNullOfOrNull { span ->
                    seen[span]?.let { ProcessCommand(span, it) }
                }
                if (run == null) node(ProcessNodeKind.STEP, label) else node(ProcessNodeKind.CHECK, label, run)
            }
            val sources = listOf(context.line(path, list.line, DOC)) +
                list.items.map { context.line(path, it.line, DOC) }
            proposal(
                context,
                "list:$path:${list.heading}",
                MarkdownFacts.words(list.heading),
                "${counted(list.items.size, "step")} listed in $path.",
                nodes,
                sources,
            )
        }
    }

    private fun skills(context: RepositoryContext, files: Map<String, String>): List<ProcessProposal> =
        files.keys.sorted().mapNotNull { path ->
            val skillName = skill.find(path)?.groupValues?.get(1)
            val commandName = command.find(path)?.groupValues?.get(1)?.let { "/$it" }
            val fallback = skillName ?: commandName ?: return@mapNotNull null
            val front = MarkdownFacts.frontMatter(files.getValue(path))
            val name = front.name?.let { if (commandName != null) "/$it" else it } ?: fallback
            val source = context.line(path, front.line, DOC)
            val nodes = listOf(node(ProcessNodeKind.STEP, shown(name)))
            proposal(context, "skill:$path", name, front.description?.let(::shown), nodes, listOf(source))
        }

    // The rules of the same analysis that gate this boundary in this repository, one gate node each.
    private fun gates(
        context: RepositoryContext,
        gated: List<GatedRule>,
        classes: Set<String>,
        label: String,
    ): List<ProcessNode> = gated.filter { rule ->
        rule.gate.actions.any { it.cls in classes && (it.repos.isEmpty() || context.name in it.repos) }
    }.take(MAX_GATES).map { ProcessNode("", ProcessNodeKind.GATE, label, rule = it) }

    // A job's commands, joined while they fit a suggestion.
    private fun joined(job: CiJob): ProcessCommand? {
        val first = job.commands.firstOrNull() ?: return null
        var text = first.second
        for ((_, next) in job.commands.drop(1)) {
            val longer = "$text && $next"
            if (longer.length > ProcessCommands.MAX_COMMAND) break
            text = longer
        }
        return ProcessCommand(text, evidence(job.path, first.first))
    }

    private fun evidence(path: String, line: Int): String = "${RepositoryContext.clean(path)}:$line"

    private fun target(target: ReleaseTarget): String =
        RepositoryContext.clean(target.path) + target.line?.let { ":$it" }.orEmpty()

    private fun node(kind: ProcessNodeKind, label: String, command: ProcessCommand? = null) =
        ProcessNode("", kind, label, command)

    // The trigger, gates and notifications are kept; the steps and checks fill what is left of MAX_NODES. Each node is
    // then numbered in order.
    private fun proposal(
        context: RepositoryContext,
        kind: String,
        title: String,
        description: String?,
        nodes: List<ProcessNode>,
        sources: List<RuleSource>,
    ): ProcessProposal {
        var left = MAX_NODES - nodes.count { it.kind in bounded }
        val kept = nodes.filter { it.kind in bounded || left-- > 0 }.mapIndexed { index, node ->
            node.copy(id = "n${index + 1}")
        }
        return ProcessProposal(
            key = "process:${context.name}:$kind",
            name = shown("${shown(title)} (${context.name})"),
            description = description,
            repo = context.name,
            nodes = kept,
            sources = sources.distinct().take(MAX_SOURCES),
        )
    }
}
