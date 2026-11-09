// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler

import run.ostrom.core.api.models.GateAction
import run.ostrom.core.api.models.WorkflowNodeKind.GATE
import run.ostrom.core.api.models.WorkflowNodeKind.STEP
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.compiler.domain.models.ActionClasses
import run.ostrom.server.compiler.domain.services.GatesCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatesCatalogTest {
    private val merge = ActionClasses.builtIn.first { it.cls == "merge" }

    @Test
    fun theShortNamesOfARepositoryAreItsTailOfThreeLettersOrMore() {
        assertEquals(listOf("acme-core", "core"), GatesCatalog.aliases("Acme-core"))
        assertEquals(listOf("api"), GatesCatalog.aliases("api"))
        assertEquals(listOf("ab-cd"), GatesCatalog.aliases("ab-cd"))
        assertTrue(GatesCatalog.mentions("ship core now", "core"))
        assertFalse(GatesCatalog.mentions("ship core-api now", "core"))
        assertTrue(GatesCatalog.covers(emptyList(), null))
        assertTrue(GatesCatalog.covers(listOf("CORE"), "acme-core"))
        assertFalse(GatesCatalog.covers(listOf("core"), null))
        assertFalse(GatesCatalog.covers(listOf("web"), "acme-core"))
    }

    @Test
    fun aDeclaredGateWinsDecidedFirstThenTheOneThatNamesTheRepository() {
        val proposed = workflow(
            "w1",
            listOf(node("g1", GATE, actions = listOf(action("merge", listOf("acme-api"))))),
            status = WorkflowStatus.PROPOSED,
        )
        val decided = workflow(
            "w2",
            listOf(node("g2", GATE, actions = listOf(action("merge")), approvers = listOf("rev-b"))),
        )
        val named = workflow("w3", listOf(node("g3", GATE, actions = listOf(action("merge", listOf("acme-api"))))))
        val other = workflow("w4", listOf(node("g4", GATE, actions = listOf(action("deploy")))))
        val gate = GatesCatalog(listOf(proposed, decided, named, other), "ops").gateFor(merge, "acme-api")
        assertEquals("g3", gate?.nodeId)
        assertEquals(listOf("ops"), gate?.approvers)
        assertEquals("production approver in Settings", gate?.reason)
        assertEquals("declared", gate?.basis)
        assertEquals(true, gate?.enforce)
        val first = GatesCatalog(listOf(decided, named), null).gateFor(merge, "acme-api")
        assertEquals("g3", first?.nodeId)
        val namedFirst = GatesCatalog(listOf(named, decided), null).gateFor(merge, "acme-api")
        assertEquals("g3", namedFirst?.nodeId, "a later gate with a lower score never replaces it")
        assertEquals(emptyList(), first?.approvers)
        val onlyProposed = GatesCatalog(listOf(proposed), null).gateFor(merge, "acme-api")
        assertEquals(false, onlyProposed?.enforce)
        assertEquals("proposed", onlyProposed?.status)
        val reasoned = workflow(
            "w5",
            listOf(node("g5", GATE, actions = listOf(action("merge"))).copy(approverReason = "owners")),
        )
        assertEquals("owners", GatesCatalog(listOf(reasoned), null).gateFor(merge, null)?.reason)
        assertEquals(
            "gate approvers of the workflow",
            GatesCatalog(listOf(decided), null).gateFor(merge, null)?.reason,
        )
    }

    @Test
    fun aGateThatDeclaredNothingIsGuessedFromItsWords() {
        val step = node("s", STEP, label = "review")
        val guessed = workflow("w2", listOf(node("g", GATE, label = "Approve the merge"), step), name = "acme-api")
        val weaker = workflow(
            "w1",
            listOf(node("g0", GATE, label = "approve merge"), step),
            status = WorkflowStatus.PROPOSED,
        )
        val few = workflow("w0", listOf(node("g9", GATE, label = "merge", detail = "now")))
        val gate = GatesCatalog(listOf(weaker, guessed, few), null).gateFor(merge, "acme-api")
        assertEquals("g", gate?.nodeId)
        assertEquals("guessed", gate?.basis)
        assertNull(GatesCatalog(listOf(few), null).gateFor(merge, "acme-api"))
        // At equal scores: a decided workflow first, then the lower id.
        val a = workflow("a", listOf(node("ga", GATE, label = "approve merge review")))
        val b = workflow("b", listOf(node("gb", GATE, label = "approve merge review")))
        val p = workflow(
            "c",
            listOf(node("gc", GATE, label = "approve merge review")),
            status = WorkflowStatus.PROPOSED,
        )
        assertEquals("ga", GatesCatalog(listOf(b, a), null).gateFor(merge, null)?.nodeId)
        assertEquals("ga", GatesCatalog(listOf(a, b), null).gateFor(merge, null)?.nodeId)
        // 4 + 1 (a word elsewhere) + 1 (decided) against 6 + 0 (proposed).
        val d = workflow("e", listOf(node("ge", GATE, label = "approve merge"), node("s", STEP, label = "review")))
        assertEquals("ge", GatesCatalog(listOf(p, d), null).gateFor(merge, null)?.nodeId)
        assertEquals("ge", GatesCatalog(listOf(d, p), null).gateFor(merge, null)?.nodeId)
    }

    @Test
    fun theBuiltInPatternsCatchTheCommonFormsOfEachAction() {
        fun classesOf(command: String) = ActionClasses.builtIn.filter { action ->
            action.patterns.any { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(command) }
        }.map { it.cls }
        // Second audit H3: options before the verb, a push that names no branch, the MCP server and the APIs.
        val gated = mapOf(
            "git push" to "push-main",
            "git -C api push origin main" to "push-main",
            "git --no-pager -c core.x=y push" to "push-main",
            "gh api -X PATCH repos/o/r/git/refs/heads/main -f sha=abc" to "push-main",
            "git push origin 1.4.0" to "push-tag",
            "git push --follow-tags" to "push-tag",
            "gh pr merge 7 --squash" to "merge",
            "mcp__github__merge_pull_request" to "merge",
            "gh api -X PUT repos/o/r/pulls/7/merge" to "merge",
            "gh api repos/o/r/merges -f base=main -f head=x" to "merge",
            "kubectl -n prod apply -f x.yaml" to "deploy",
            "helm --kube-context prod upgrade api ./chart" to "deploy",
            "terraform -chdir=infra apply" to "deploy",
            "docker image push ghcr.io/o/api" to "deploy",
            "docker buildx build --push ." to "deploy",
            "pnpm publish" to "deploy",
            "yarn npm publish" to "deploy",
            "gh workflow run 1234" to "deploy",
            "gh api repos/o/r/actions/workflows/1/dispatches" to "deploy",
        )
        for ((command, cls) in gated) assertTrue(cls in classesOf(command), "$command is $cls")
        assertEquals(emptyList(), classesOf("git stash push"))
        assertEquals(emptyList(), classesOf("kubectl -n prod get pods"))
        assertEquals(emptyList(), classesOf("git log --oneline"))
    }

    @Test
    fun aProposedWorkflowNeverWeakensADecidedGate() {
        val pushMain = ActionClasses.builtIn.first { it.cls == "push-main" }
        val decided = workflow("w1", listOf(node("g1", GATE, label = "Push to main", approvers = listOf("rev-b"))))
        val narrowing = workflow(
            "w0",
            listOf(node("g0", GATE, actions = listOf(GateAction("push-main", "Push", listOf("a^"), emptyList())))),
            status = WorkflowStatus.PROPOSED,
        )
        val entry = GatesCatalog(listOf(narrowing, decided), null).catalog("acme-api")
            .first { it.action.cls == "push-main" }
        assertEquals(pushMain.patterns + "a^", entry.action.patterns, "second audit H2: a proposal only adds patterns")
        assertEquals("g1", entry.gate?.nodeId, "the decided gate, guessed from its words, stays")
        assertEquals(true, entry.gate?.enforce)
        assertEquals("guessed", entry.gate?.basis)
        // Without a decided gate, the proposed ones are what there is: shadows.
        assertEquals("g0", GatesCatalog(listOf(narrowing), null).gateFor(pushMain, null)?.nodeId)
        val guessedProposal =
            workflow("w2", listOf(node("g2", GATE, label = "Push to main")), status = WorkflowStatus.PROPOSED)
        val shadow = GatesCatalog(listOf(guessedProposal), null).gateFor(pushMain, null)
        assertEquals("g2", shadow?.nodeId)
        assertEquals(false, shadow?.enforce)
    }

    @Test
    fun theCatalogAddsTheCustomActionsAndTheDeclaredPatterns() {
        val custom = GateAction("db-drop", "", listOf("\\bdrop\\b"), emptyList())
        val tooled = GateAction("ship", "Ship", listOf("ship"), emptyList(), tools = listOf("mcp"))
        val empty = GateAction("nothing", "Nothing", emptyList(), emptyList())
        val again = GateAction("db-drop", "Again", listOf("other"), emptyList())
        val builtIn = GateAction("merge", "Merge", listOf("my-merge"), listOf("web"))
        val actions = listOf(custom, tooled, empty, again, builtIn, action("deploy"))
        val wf = workflow("w", listOf(node("g", GATE, actions = actions), node("g0", GATE, label = "Nothing declared")))
        val catalog = GatesCatalog(listOf(wf), null).catalog("acme-api")
        val classes = catalog.map { it.action.cls }
        assertEquals(ActionClasses.builtIn.map { it.cls } + listOf("db-drop", "ship"), classes)
        val drop = catalog.first { it.action.cls == "db-drop" }
        assertEquals("db-drop", drop.action.label)
        assertEquals(listOf("Bash"), drop.action.tools)
        assertEquals(listOf("mcp"), catalog.first { it.action.cls == "ship" }.action.tools)
        assertEquals(merge.patterns, catalog.first { it.action.cls == "merge" }.action.patterns)
        val web = GatesCatalog(listOf(wf), null).catalog("web")
        assertEquals(listOf("my-merge"), web.first { it.action.cls == "merge" }.action.patterns)
    }

    @Test
    fun aWorkflowLimitedToRepositoriesGatesThemOnlyAndNeverTheOrganization() {
        // R6: a decided gate of a workflow limited to acme/docs neither gates acme/api nor adds its custom class there.
        val custom = GateAction("db-drop", "Drop", listOf("\\bdrop\\b"), emptyList())
        val docsOnly = workflow("docs-only", listOf(node("g", GATE, actions = listOf(action("merge"), custom))))
        val catalogue = GatesCatalog.of(listOf(limited(docsOnly, "acme/docs", "acme/web-*")), emptyList(), "ops")

        val docs = catalogue.catalog("acme/docs")
        assertEquals("docs-only", docs.first { it.action.cls == "merge" }.gate?.workflowId)
        assertTrue(docs.any { it.action.cls == "db-drop" })
        val site = catalogue.catalog("ACME/web-site")
        assertEquals("docs-only", site.first { it.action.cls == "merge" }.gate?.workflowId)
        for (other in listOf("acme/api", null)) {
            val catalog = catalogue.catalog(other)
            assertNull(catalog.first { it.action.cls == "merge" }.gate, "not in the catalogue of $other")
            assertFalse(catalog.any { it.action.cls == "db-drop" })
        }
        assertEquals(listOf("acme/docs", "acme/web-*"), catalogue.repositories())
    }
}
