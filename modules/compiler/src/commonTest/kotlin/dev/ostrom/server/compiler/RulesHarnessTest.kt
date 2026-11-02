// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler

import dev.ostrom.core.api.models.WorkflowNodeKind.GATE
import dev.ostrom.core.api.models.WorkflowStatus
import dev.ostrom.server.compiler.domain.models.ActionClasses
import dev.ostrom.server.compiler.domain.models.CatalogEntry
import dev.ostrom.server.compiler.domain.services.GatesCatalog
import dev.ostrom.server.compiler.domain.services.RulesHarness
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RulesHarnessTest {
    private val repos = listOf("acme-api", "acme-backend", "acme-web")
    private val rules = listOf(
        rule("r-own", scope = listOf("acme-api"), strength = "advised"),
        rule("r-org", confidence = 0.9, group = ""),
        rule("r-short", sources = listOf("api@main")),
        rule("r-other", scope = listOf("backend")),
        rule("r-web", scope = listOf("acme-web")),
        rule("r-proposed", status = "proposed", group = "Tests", scope = List(6) { "s$it" }),
        rule("r-rejected", status = "rejected"),
        rule("r-edited", status = "edited", confidence = 0.1),
    )
    private val decisions = listOf(
        decision("r-org", kind = "rule", at = "2026-10-01", receipt = "rcpt_old"),
        decision("r-org", kind = "rule", at = "2026-10-02", receipt = "rcpt_new"),
        decision("r-org", kind = "rule", at = "2026-10-03", receipt = null),
        decision("r-org", kind = "workflow", at = "2026-10-04", receipt = "rcpt_wf"),
    )
    private val harness = RulesHarness(rules, decisions, repos)

    @Test
    fun aRepositoryGetsItsRulesAndTheOrganizationsDecidedFirst() {
        val picked = harness.rulesFor("acme-api")
        assertEquals(listOf("r-short", "r-org", "r-edited", "r-own", "r-proposed"), picked.map { it.rule.id })
        assertEquals(listOf("repo", "org", "org", "repo", "org"), picked.map { it.reach })
        assertEquals("rcpt_new", picked.first { it.rule.id == "r-org" }.decisionReceipt)
        val org = harness.rulesFor(null)
        assertEquals("repo", org.first { it.rule.id == "r-own" }.reach)
        assertEquals("repo", org.first { it.rule.id == "r-other" }.reach)
        assertEquals("org", org.first { it.rule.id == "r-short" }.reach)
    }

    @Test
    fun theFilesSayTheRulesTheGatesAndWhoSaysYes() {
        val gated = workflow("w", listOf(node("g", GATE, label = "Merge gate", actions = listOf(action("merge")))))
        val proposed = workflow(
            "p",
            listOf(node("g2", GATE, label = "Deploy", actions = listOf(action("deploy")), approvers = listOf("a"))),
            status = WorkflowStatus.PROPOSED,
        )
        val catalog = GatesCatalog(listOf(gated, proposed), null).catalog(null)
        val picked = harness.rulesFor(null)
        val content = harness.build(null, "https://x", "h1-abc", picked, catalog)
        val files = content.files.toMap()
        assertEquals(content.digest, harness.digest(null, picked, catalog))
        assertEquals(
            listOf("skills/ostrom-general/SKILL.md", "skills/ostrom-tests/SKILL.md", ".claude-plugin/plugin.json") +
                listOf("ostrom/gates.json", "ostrom/AGENTS-block.md"),
            content.files.map { it.first },
        )
        val general = files.getValue("skills/ostrom-general/SKILL.md")
        assertTrue(general.contains("# General: the team's rules\n"), general)
        assertTrue(general.contains("**[SHOULD]** Rule r-own."), general)
        assertTrue(general.contains("· decision rcpt_new"), general)
        assertTrue(general.contains("Team rules for General (6 decided, 0 proposed)"), general)
        val tests = files.getValue("skills/ostrom-tests/SKILL.md")
        assertTrue(tests.contains("## Proposed (not decided yet)\n\n- **[proposed]** Rule r-proposed."), tests)
        assertTrue(tests.contains("· scope: s0, s1, s2, s3</sub>"), tests)
        assertTrue(!tests.contains("## Decided"), tests)
        val block = files.getValue("ostrom/AGENTS-block.md")
        assertTrue(block.contains("This repository works under"), block)
        assertTrue(block.contains("- Merge a pull request: Merge gate → production approver\n"), block)
        val deploy = "- Deploy to production: Deploy → a (proposed gate: logged, not enforced)"
        assertTrue(block.contains(deploy), block)
        assertTrue(files.getValue(".claude-plugin/plugin.json").contains("Ostrom harness for the organisation"))
        assertTrue(files.getValue("ostrom/gates.json").contains("\"repo\": null"))
        assertTrue(files.getValue("ostrom/gates.json").contains("\"gate\": null"))
    }

    @Test
    fun withoutADecidedRuleNorAGateTheBlockSaysSo() {
        val lone = RulesHarness(listOf(rule("r", status = "proposed", strength = "advised")), emptyList(), emptyList())
        val content = lone.build("acme-api", "https://x", "h1-abc", lone.rulesFor("acme-api"), emptyList())
        val block = content.files.toMap().getValue("ostrom/AGENTS-block.md")
        assertTrue(block.contains("No rule decided yet for this repository"), block)
        assertTrue(!block.contains("need a human yes"), block)
        val skill = content.files.toMap().getValue("skills/ostrom-general/SKILL.md")
        assertTrue(skill.contains("Use before changing General."), skill)
        val empty = RulesHarness(emptyList(), emptyList(), emptyList())
        assertEquals(
            listOf(".claude-plugin/plugin.json", "ostrom/gates.json", "ostrom/AGENTS-block.md"),
            empty.build(null, "https://x", "h1", emptyList(), emptyList()).files.map { it.first },
        )
        val ungated = CatalogEntry(ActionClasses.builtIn.first(), gate = null)
        assertEquals(JsonNull, RulesHarness.gateJson(ungated), "an action no gate covers")
    }
}
