// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler.domain.services

import run.ostrom.core.receipts.Digests
import run.ostrom.server.compiler.domain.models.CatalogEntry
import run.ostrom.server.compiler.domain.models.DecisionDoc
import run.ostrom.server.compiler.domain.models.RuleDoc
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A rule the harness carries, with what its receipt names.
 *
 * @property rule The rule.
 * @property reach `repo` when it names the repository, `org` when it names no known repository.
 * @property digest Its digest.
 * @property decisionReceipt The receipt of its last decision.
 */
data class HarnessRule(val rule: RuleDoc, val reach: String, val digest: String, val decisionReceipt: String?)

/**
 * The rules harness of a repository (`bridge/harness.ts` `buildHarness`), the part a session package carries: one
 * skill per rule group, the gates catalogue, the AGENTS block and the plugin manifest.
 *
 * @property digest What the harness says (rules and gates), whatever the time.
 * @property rules The rules it carries, in order.
 * @property files Path → content, under the session plugin.
 */
data class RulesHarnessContent(val digest: String, val rules: List<HarnessRule>, val files: List<Pair<String, String>>)

/**
 * Builds the rules harness, byte for byte as the prototype.
 *
 * @param rules The organization's rules.
 * @param decisions The signed decisions (the receipt of a rule's last decision is cited).
 * @param repos The organization's repository names.
 */
class RulesHarness(
    private val rules: List<RuleDoc>,
    private val decisions: List<DecisionDoc>,
    private val repos: List<String>,
) {
    /**
     * The rules of a repository: those that name it, and those that name no known repository; decided first, then
     * required, then the repository's own, then by confidence.
     *
     * @param repo The repository, or null for the organization.
     * @return The rules with their reach.
     */
    fun rulesFor(repo: String?): List<HarnessRule> = rules
        .filter { it.status != "rejected" }
        .mapNotNull { rule ->
            reach(rule, repo)?.let { HarnessRule(rule, it, Digests.digestOf(rule.raw), receiptOf(rule.id)) }
        }
        .sortedWith(
            compareByDescending<HarnessRule> { it.rule.decided }
                .thenByDescending { it.rule.strength == "required" }
                .thenByDescending { it.reach == "repo" }
                .thenByDescending { it.rule.confidence },
        )

    private fun reach(rule: RuleDoc, repo: String?): String? {
        val text = (rule.scope + rule.sourceRefs).joinToString(" | ")
        // A repository is named by its full name, or by a short name distinctive enough (6 letters or more).
        val named = repos.any { name ->
            GatesCatalog.aliases(name).any { alias ->
                (alias == name.lowercase() || alias.length >= DISTINCT) && GatesCatalog.mentions(text, alias)
            }
        }
        val own = repo != null && GatesCatalog.aliases(repo).any { GatesCatalog.mentions(text, it) }
        return when {
            repo == null -> if (named) "repo" else "org"
            own -> "repo"
            named -> null
            else -> "org"
        }
    }

    private fun receiptOf(ruleId: String): String? = decisions
        .filter { it.subjectKind == "rule" && it.subjectId == ruleId && it.receiptId != null }
        .maxByOrNull { it.at }
        ?.receiptId

    /**
     * The digest of a harness: the repository, its rules (id, status, digest) and its gates catalogue.
     *
     * @param repo The repository.
     * @param picked Its rules.
     * @param catalog Its gates catalogue.
     * @return The digest.
     */
    fun digest(repo: String?, picked: List<HarnessRule>, catalog: List<CatalogEntry>): String = Digests.digestOf(
        buildJsonObject {
            put("repo", if (repo == null) JsonNull else JsonPrimitive(repo))
            putJsonArray("rules") {
                picked.forEach { rule ->
                    add(
                        buildJsonObject {
                            put("id", rule.rule.id)
                            put("status", rule.rule.status)
                            put("digest", rule.digest)
                        },
                    )
                }
            }
            putJsonArray("gates") {
                catalog.forEach { entry ->
                    add(
                        buildJsonObject {
                            put("c", entry.action.cls)
                            put("p", buildJsonArray { entry.action.patterns.forEach { add(it) } })
                            put("g", gateJson(entry))
                        },
                    )
                }
            }
        },
    )

    /**
     * The harness files of the session plugin.
     *
     * @param repo The repository.
     * @param serverUrl The server's URL.
     * @param version The harness version (`h<stamp>-<digest>`).
     * @param picked Its rules.
     * @param catalog Its gates catalogue.
     * @return The content.
     */
    fun build(
        repo: String?,
        serverUrl: String,
        version: String,
        picked: List<HarnessRule>,
        catalog: List<CatalogEntry>,
    ): RulesHarnessContent {
        val digest = digest(repo, picked, catalog)
        val groups = picked.groupBy { it.rule.group?.ifEmpty { null } ?: "General" }.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, List<HarnessRule>>> { group ->
                    group.value.count { it.rule.decided }
                }
                    .thenByDescending { it.value.size },
            )
        val skills = groups.map { "ostrom-${JsText.slug(it.key, "general")}" }
        val files = mutableListOf<Pair<String, String>>()
        groups.zip(skills).forEach { (group, name) ->
            files += "skills/$name/SKILL.md" to skill(name, group.key, repo, group.value.take(MAX_PER_SKILL), version)
        }
        val gatesJson = buildJsonObject {
            put("version", version)
            put("digest", digest)
            put("repo", if (repo == null) JsonNull else JsonPrimitive(repo))
            put("server", serverUrl)
            put("failMode", "closed")
            put("waitSeconds", WAIT_SECONDS)
            put("actions", buildJsonArray { catalog.forEach { add(actionJson(it)) } })
        }
        val plugin = buildJsonObject {
            put("name", "ostrom")
            put("version", version.replaceFirst(Regex("^h"), "0.0.0-"))
            put("description", "Ostrom harness for ${repo ?: "the organisation"}: decided rules, gates and receipts")
        }
        files += ".claude-plugin/plugin.json" to JsText.pretty(plugin)
        files += "ostrom/gates.json" to JsText.pretty(gatesJson)
        val head = "## Team harness (Ostrom $version)"
        val where = repo?.let { " ($it)" }.orEmpty()
        val intro = "This repository$where works under the rules the team decided in Ostrom. " +
            "Digest `${digest.take(BLOCK_DIGEST)}`."
        files += "ostrom/AGENTS-block.md" to agentsBlock(listOf(head, "", intro), picked, catalog, skills)
        return RulesHarnessContent(digest, picked, files)
    }

    private fun ruleLine(rule: HarnessRule): String {
        val r = rule.rule
        val tag = when {
            !r.decided -> "proposed"
            r.strength == "required" -> "MUST"
            else -> "SHOULD"
        }
        val receipt = rule.decisionReceipt?.let { " · decision $it" }.orEmpty()
        val scope = if (r.scope.isEmpty()) "" else " · scope: ${r.scope.take(SCOPE_SHOWN).joinToString(", ")}"
        val digest = rule.digest.take(SHORT_DIGEST)
        return "- **[$tag]** ${r.statement.trim()}  \n  <sub>${r.id} · digest $digest$receipt$scope</sub>"
    }

    private fun skill(name: String, group: String, repo: String?, picked: List<HarnessRule>, version: String): String {
        val decided = picked.filter { it.rule.decided }
        val proposed = picked.filterNot { it.rule.decided }
        val scopes = picked.flatMap { it.rule.scope }.distinct().take(SCOPES_IN_DESCRIPTION).joinToString(", ")
        val inRepo = repo?.let { " in $it" }.orEmpty()
        // As the prototype: only the second sentence is cut.
        val counts = "${decided.size} decided, ${proposed.size} proposed"
        val description = "Team rules for $group$inRepo ($counts), from Ostrom. " +
            "Use before changing ${scopes.ifEmpty { group }}.".take(MAX_DESCRIPTION)
        val lines = mutableListOf(
            "---",
            "name: $name",
            "description: ${JsText.quote(description)}",
            "---",
            "",
            "# $group: the team's rules${repo?.let { " for $it" }.orEmpty()}",
            "",
            "Harness $version, exported by Ostrom. **MUST** and **SHOULD** rules were decided by the team " +
                "(accepted or edited, each with a signed decision receipt when one exists).",
            "Rules marked **proposed** were found in the history but nobody decided them yet: follow them when in " +
                "doubt, never cite them as decided.",
            "",
        )
        if (decided.isNotEmpty()) lines += listOf("## Decided", "") + decided.map(::ruleLine) + ""
        if (proposed.isNotEmpty()) lines += listOf("## Proposed (not decided yet)", "") + proposed.map(::ruleLine) + ""
        lines += listOf("Ask `rules_for` (MCP server `ostrom`) for the rules of one path.", "")
        return lines.joinToString("\n")
    }

    private fun agentsBlock(
        header: List<String>,
        picked: List<HarnessRule>,
        catalog: List<CatalogEntry>,
        skills: List<String>,
    ): String {
        val top = picked.map { it.rule }.filter { it.decided && it.strength == "required" }.take(MAX_ALWAYS)
        val gated = catalog.mapNotNull { entry -> entry.gate?.let { entry.action.label to it } }
        val lines = mutableListOf("<!-- ostrom:begin (generated by `ostrom sync`, do not edit inside this block) -->")
        lines += header
        lines += ""
        lines += if (top.isEmpty()) {
            listOf("No rule decided yet for this repository: the skills list what was found, marked proposed.", "")
        } else {
            listOf("Always:") + top.map { "- ${it.statement.trim()} (${it.id})" } + ""
        }
        val named = skills.joinToString(", ") { "`$it`" }
        lines += "Rules by domain: skills $named. For one file: `rules_for(path)` on the MCP server `ostrom`."
        lines += ""
        if (gated.isNotEmpty()) {
            lines += "Actions that need a human yes first (the session pauses until the answer):"
            lines += gated.map { (label, gate) ->
                val who = gate.approvers.joinToString(" or ").ifEmpty { "production approver" }
                val proposed = if (gate.enforce) "" else " (proposed gate: logged, not enforced)"
                "- $label: ${gate.label} → $who$proposed"
            }
            lines += ""
        }
        lines += "Every session ends with a signed receipt (rules in force, gates asked, who said yes)."
        lines += "<!-- ostrom:end -->"
        lines += ""
        return lines.joinToString("\n")
    }

    /** JSON of the catalogue, shared with the gates catalogue route. */
    companion object {
        private const val DISTINCT = 6
        private const val MAX_PER_SKILL = 60
        private const val WAIT_SECONDS = 300
        private const val SCOPE_SHOWN = 4
        private const val SHORT_DIGEST = 12
        private const val SCOPES_IN_DESCRIPTION = 8
        private const val MAX_DESCRIPTION = 900
        private const val MAX_ALWAYS = 12
        private const val BLOCK_DIGEST = 16

        /**
         * A gate as the prototype writes it (`GateRef`), or `null`.
         *
         * @param entry The catalogue line.
         * @return Its JSON.
         */
        fun gateJson(entry: CatalogEntry): JsonElement {
            val gate = entry.gate ?: return JsonNull
            return buildJsonObject {
                put("workflowId", gate.workflowId)
                put("workflowName", gate.workflowName)
                put("nodeId", gate.nodeId)
                put("label", gate.label)
                put("approvers", buildJsonArray { gate.approvers.forEach { add(it) } })
                put("status", gate.status)
                put("enforce", gate.enforce)
                put("basis", gate.basis)
                put("reason", gate.reason)
            }
        }

        /**
         * A catalogue line as the prototype writes it: class, label, tools, patterns, keywords, gate.
         *
         * @param entry The catalogue line.
         * @return Its JSON.
         */
        fun actionJson(entry: CatalogEntry): JsonObject = buildJsonObject {
            put("class", entry.action.cls)
            put("label", entry.action.label)
            put("tools", buildJsonArray { entry.action.tools.forEach { add(it) } })
            put("patterns", buildJsonArray { entry.action.patterns.forEach { add(it) } })
            put("keywords", buildJsonArray { entry.action.keywords.forEach { add(it) } })
            put("gate", gateJson(entry))
        }
    }
}
