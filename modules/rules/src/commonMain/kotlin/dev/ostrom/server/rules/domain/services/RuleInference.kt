// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules.domain.services

import dev.ostrom.server.rules.domain.models.RepositorySnapshot
import dev.ostrom.server.rules.domain.models.RuleGate
import dev.ostrom.server.rules.domain.models.RuleGateAction
import dev.ostrom.server.rules.domain.models.RuleProposal
import dev.ostrom.server.rules.domain.models.RuleStrength
import dev.ostrom.server.rules.domain.services.RepositoryContext.Companion.counted
import dev.ostrom.server.rules.domain.services.RepositoryContext.Companion.quoted

/**
 * The rules a repository's facts propose, without a model: how it builds and checks a change, which files need a yes
 * (secrets, CI, infrastructure, migrations), who reviews what ([ReviewInference]). Each proposal says what was
 * counted and how sure it is; the same repository always gives the same proposals.
 */
object RuleInference {
    private const val PINNED = 0.9
    private const val UNPINNED = 0.7
    private const val LANGUAGES_ONLY = 0.6
    private const val TOP_LANGUAGES = 3
    private const val PERCENT = 100
    private const val BUILD = "Build"

    /** Said of every rule found in a repository whose file list GitHub cut. */
    const val TRUNCATED: String = " GitHub cut the file list of this repository: some files were not seen."

    private val areas = mapOf(
        AreaKind.SECRETS to AreaSpec(
            cls = "secret-files",
            label = "Read or change a secret file",
            tools = listOf("Read") + RepositoryContext.editTools,
            confidence = 0.9,
            statement = { repo, files, _ -> "Agents never read, change or print the secret files of $repo ($files)." },
            why = { branch ->
                "These files are on `$branch`: a secret in git reaches everyone who can clone the repository. " +
                    "Move them to a secret store and rotate what they hold."
            },
        ),
        AreaKind.CI to AreaSpec(
            cls = "edit-ci",
            label = "Change the CI",
            tools = RepositoryContext.editTools,
            confidence = 0.85,
            statement = { repo, files, yes -> "A change to the CI of $repo ($files) needs a yes$yes." },
            why = { branch -> "The CI runs with the repository's secrets and decides what reaches `$branch`." },
        ),
        AreaKind.INFRA to AreaSpec(
            cls = "edit-infra",
            label = "Change the infrastructure",
            tools = RepositoryContext.editTools,
            confidence = 0.8,
            statement = { repo, files, yes -> "A change to the infrastructure of $repo ($files) needs a yes$yes." },
            why = { _ -> "These files describe what runs in production." },
        ),
        AreaKind.MIGRATIONS to AreaSpec(
            cls = "migration",
            label = "Add or change a migration",
            tools = RepositoryContext.editTools,
            confidence = 0.8,
            statement = { repo, files, yes ->
                "In $repo, never edit a migration that already ran ($files): add a new one, and a new one needs " +
                    "a yes$yes."
            },
            why = { _ ->
                "A migration that already ran is not run again: editing it leaves the databases behind the code."
            },
        ),
    )

    /**
     * Proposes the rules of one repository.
     *
     * @param snapshot What the analysis read of it.
     * @return Its proposals, by kind: build, sensitive files, code owners, branch.
     */
    fun infer(snapshot: RepositorySnapshot): List<RuleProposal> {
        val context = RepositoryContext(snapshot)
        val proposals = build(context) +
            SensitiveFiles.areas(snapshot.tree.paths).map { area(context, it) } +
            ReviewInference.owners(context) +
            ReviewInference.branch(context)
        return if (snapshot.tree.truncated) proposals.map { it.copy(why = it.why + TRUNCATED) } else proposals
    }

    private fun build(context: RepositoryContext): List<RuleProposal> {
        val paths = context.snapshot.tree.paths
        val languages = BuildFacts.languages(paths)
        val code = languages.takeIf { it.isNotEmpty() }?.let(::languageSentence)
        val tools = BuildFacts.buildTools(paths, context.snapshot.files[RepositoryFacts.PACKAGE_JSON])
        if (tools.isEmpty()) return listOfNotNull(languagesNote(context, languages, code))
        return tools.map { tool ->
            val statement = tool.command?.let { "In ${context.name}, check a change with `$it` before pushing it." }
                ?: "In ${context.name}, build with ${tool.name}."
            val found = "Found ${quoted(tool.evidence)} at the root" +
                if (tool.pinned) ", which pins the tool." else "."
            val output = if (tool.command == null) "rule" else "check"
            proposal(context, "build:${tool.name.lowercase()}", statement, output)
                .copy(
                    why = listOfNotNull(found, code).joinToString(" "),
                    sources = tool.evidence.map { context.file(it) },
                    confidence = if (tool.pinned) PINNED else UNPINNED,
                )
        }
    }

    // A repository with code but no build tool: what it is written in.
    private fun languagesNote(context: RepositoryContext, languages: List<LanguageShare>, code: String?) =
        languages.firstOrNull()?.let { top ->
            proposal(context, "languages", "${context.name} is written mostly in ${top.name}.", "note")
                .copy(why = code.orEmpty(), confidence = LANGUAGES_ONLY)
        }

    private fun languageSentence(languages: List<LanguageShare>): String {
        val total = languages.sumOf { it.files }
        val shares = languages.take(TOP_LANGUAGES).joinToString(", ") {
            "${it.name} ${(it.files * PERCENT + total / 2) / total} %"
        }
        return "Code: $shares of ${counted(total, "file")}."
    }

    private fun area(context: RepositoryContext, area: SensitiveArea): RuleProposal {
        val spec = areas.getValue(area.kind)
        val approvers = CodeOwners.logins(CodeOwners.ownersOf(context.owners, area.evidence.first()))
        val yes = if (approvers.isEmpty()) "" else " from " + approvers.joinToString(", ") { "@$it" }
        val sourceKind = if (area.kind == AreaKind.CI) "ci" else RepositoryContext.FILE
        return RuleProposal(
            key = "area:${context.name}:${area.kind.name.lowercase()}",
            statement = spec.statement(context.name, quoted(area.prefixes), yes),
            output = "gate",
            group = area.kind.group,
            scope = area.prefixes.map { "${context.name}/$it" },
            sources = area.evidence.map { context.file(it, sourceKind) },
            why = spec.why(context.branch),
            strength = RuleStrength.REQUIRED,
            confidence = spec.confidence,
            gate = RuleGate(
                actions = listOf(
                    RuleGateAction(
                        cls = spec.cls,
                        label = spec.label,
                        patterns = area.prefixes.map(CodeOwners::prefixPattern),
                        repos = listOf(context.name),
                        tools = spec.tools,
                    ),
                ),
                approvers = approvers,
                reason = context.codeownersPath?.takeIf { approvers.isNotEmpty() }?.let { "code owners in $it" },
            ),
        )
    }

    /**
     * A proposal of one repository, without sources, reason or gate yet.
     *
     * @param context The repository.
     * @param kind What it was found from, unique in the repository.
     * @param statement What it says.
     * @param output What it produces.
     * @return The proposal.
     */
    internal fun proposal(context: RepositoryContext, kind: String, statement: String, output: String) = RuleProposal(
        key = "$kind:${context.name}",
        statement = statement,
        output = output,
        group = BUILD,
        scope = listOf(context.name),
        sources = emptyList(),
        why = "",
        strength = RuleStrength.RECOMMENDED,
        confidence = UNPINNED,
        gate = null,
    )

    // The words of a sensitive-files rule and the gate it puts.
    private class AreaSpec(
        val cls: String,
        val label: String,
        val tools: List<String>,
        val confidence: Double,
        val statement: (repo: String, files: String, yes: String) -> String,
        val why: (branch: String) -> String,
    )
}
