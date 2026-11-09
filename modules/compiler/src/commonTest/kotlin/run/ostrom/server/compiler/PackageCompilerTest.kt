// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler

import run.ostrom.core.api.models.NodeRun
import run.ostrom.core.api.models.NodeRunOn
import run.ostrom.core.api.models.NodeSuggestions
import run.ostrom.core.api.models.PackageMode
import run.ostrom.core.api.models.RunLocation
import run.ostrom.core.api.models.WorkflowNodeKind.AGENT
import run.ostrom.core.api.models.WorkflowNodeKind.CHECK
import run.ostrom.core.api.models.WorkflowNodeKind.GATE
import run.ostrom.core.api.models.WorkflowNodeKind.NOTIFY
import run.ostrom.core.api.models.WorkflowNodeKind.STEP
import run.ostrom.core.api.models.WorkflowNodeKind.TRIGGER
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.compiler.domain.services.PackageCompiler
import run.ostrom.server.compiler.domain.services.PackageSigner
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackageCompilerTest {
    @Test
    fun aWorkflowAppliesWhenItsGateCoversTheRepositoryOrItNamesIt() {
        val viaGate = workflow("a", listOf(node("g", GATE, actions = listOf(action("deploy")))))
        val named = workflow("b", listOf(node("s", STEP, label = "Ship acme-api")))
        val db = action("db", listOf("ACME-API"), listOf("x"))
        val declared = workflow("c", listOf(node("g", GATE, actions = listOf(db))))
        // The catalog keeps one gate per action: the second workflow declaring the repository applies all the same.
        val declaredToo = workflow("f", listOf(node("g", GATE, actions = listOf(db))))
        val web = node("s", STEP, label = "Ship the web", detail = "Then the CDN.")
        val other = workflow("d", listOf(web, node("g", GATE)), domain = "web")
        val elsewhere = node("g", GATE, actions = listOf(action("db", listOf("acme-web"), listOf("x"))))
        val short = workflow("e", listOf(node("s", STEP, label = "api"), elsewhere))
        val all = listOf(viaGate, named, declared, other, short, declaredToo)
        assertEquals(listOf("a", "b", "c", "f"), PackageCompiler.workflowsFor(inputs(all)).map { it.workflow.id })
        assertEquals(all.map { it.id }, PackageCompiler.workflowsFor(inputs(all, repo = null)).map { it.workflow.id })
    }

    @Test
    fun aWorkflowThatListsItsRepositoriesAppliesToThemOnlyWhateverItsStatus() {
        // R6: the alias and the text no longer bring in a workflow limited to another repository, nor its gate.
        val deploy = node("g", GATE, actions = listOf(action("deploy")))
        val named = workflow("named", listOf(node("s", STEP, label = "Ship acme/api")))
        val elsewhere = workflow("elsewhere", listOf(node("s", STEP, label = "Ship acme/api"), deploy))
        val unrelated = listOf(node("s", STEP, label = "Unrelated"))
        val observed = workflow("everywhere", unrelated, status = WorkflowStatus.PROPOSED)
        val docs = listOf(doc(named), limited(elsewhere, "acme/docs"), limited(observed, "ACME/*"))
        val api = inputs(emptyList(), repo = "acme/api").copy(workflows = docs)

        assertEquals(listOf("named", "everywhere"), PackageCompiler.workflowsFor(api).map { it.workflow.id })
        val docsRepo = api.copy(repo = "acme/docs")
        assertEquals(listOf("elsewhere", "everywhere"), PackageCompiler.workflowsFor(docsRepo).map { it.workflow.id })
        val organization = api.copy(repo = null)
        assertEquals(docs, PackageCompiler.workflowsFor(organization))
    }

    @Test
    fun nodesAreOrderedFromTheStartBreadthFirstThenTheUnreachedOnes() {
        val wf = workflow(
            "w",
            listOf(node("b", STEP), node("a", TRIGGER), node("c", STEP), node("d", STEP), node("x", STEP)),
            listOf("a" to "b", "a" to "c", "b" to "d", "c" to "d", "d" to "x", "x" to "d", "b" to "ghost"),
        )
        assertEquals(listOf("a", "b", "c", "d", "x"), PackageCompiler.orderOf(wf).map { it.id })
        val ring = workflow(
            "r",
            listOf(node("p", STEP), node("q", STEP), node("lone", STEP)),
            listOf("p" to "q", "q" to "p", "lone" to "lone"),
        )
        assertEquals(listOf("p", "q", "lone"), PackageCompiler.orderOf(ring).map { it.id })
    }

    @Test
    fun aScriptRunsOnlyFromADecisionOfACodePublisher() = runTest {
        val check = node("c", CHECK, label = "push", run = NodeRun(command = " make ", timeoutSec = 1, network = true))
        val decided = workflow("w", listOf(check))
        val allowed = compiled(inputs(listOf(decided), codePublishers = listOf("DEV-A"))).scripts.single()
        assertEquals(PackageMode.ENFORCE, allowed.mode)
        assertEquals("make", allowed.command)
        assertEquals(5, allowed.timeoutSec)
        assertEquals(true, allowed.network)
        val refused = compiled(inputs(listOf(decided), codePublishers = listOf("rev-b")))
        assertEquals("dev-a may not publish code (OSTROM_CODE_PUBLISHERS)", refused.scripts.single().approval.reason)
        assertEquals(PackageMode.SHADOW, refused.hooks.single().mode)
        assertEquals(refused.scripts.single().approval.reason, refused.hooks.single().why)
        val undecided = compiled(inputs(listOf(decided), decisions = emptyList())).scripts.single()
        assertEquals("no signed decision on this workflow", undecided.approval.reason)
        val long = node("c", CHECK, label = "push", run = NodeRun(command = "make", timeoutSec = 9999))
        assertEquals(540, compiled(inputs(listOf(workflow("w", listOf(long))))).scripts.single().timeoutSec)
        val proposed = compiled(inputs(listOf(decided.copy(status = WorkflowStatus.PROPOSED))))
        assertEquals("workflow proposed, not decided: shadow only", proposed.scripts.single().approval.reason)
        assertTrue(proposed.body.getValue("context").jsonPrimitive.content.contains("[proposed: logged only]"))
    }

    @Test
    fun aSuggestedCommandCompilesInTheShadowWithTheSuggestedTrigger() = runTest {
        val check = node(
            "c",
            CHECK,
            label = "x",
            suggested = NodeSuggestions(command = text("npm test"), on = texts("deploy")),
            ruleIds = listOf("r1", "missing"),
        )
        val unproved = text("make").copy(evidence = emptyList())
        val noProof = node("d", CHECK, label = "y", suggested = NodeSuggestions(command = unproved))
        val body = compiled(inputs(listOf(workflow("w", listOf(check, noProof))), rules = listOf(rule("r1"))))
        val script = body.scripts.first()
        assertEquals(PackageMode.SHADOW, script.mode)
        assertEquals("suggested by the analysis (evidence): nobody has taken it yet", script.approval.reason)
        assertEquals(listOf("evidence"), script.suggested?.evidence)
        assertEquals(true, body.hooks.single().suggested)
        assertEquals(listOf("deploy"), body.hooks.single().classes)
        assertEquals("suggested by the analysis (no proof): nobody has taken it yet", body.scripts[1].approval.reason)
        val skill = body.files.single { it.path == "skills/ostrom-wf-w/SKILL.md" }.content
        assertTrue(skill.contains("Check it yourself before the action"), skill)
        assertTrue(skill.contains("- Rule r1. (r1)\n"), skill)
        assertTrue(!skill.contains("missing"), skill)
    }

    @Test
    fun gatesNotifiesAndAgentsCompileWithTheirDefaults() = runTest {
        val gate = node("g", GATE, label = "Ship it")
        val notify = node("n", NOTIFY, label = "push done", owner = "ops")
        val suggested = node("m", NOTIFY, label = "push", suggested = NodeSuggestions(channel = text("slack:#x")))
        val chosen = node("o", NOTIFY, label = "push", run = NodeRun(channel = "slack:#rel", message = "Hi"))
        val silent = node("q", NOTIFY, label = "nothing", run = NodeRun(message = ""))
        val agent = node("a", AGENT, label = "Look", detail = "  Read it.  ")
        val bare = node("b", AGENT, label = "Bare")
        val check = node("c", CHECK, label = "Tests", run = NodeRun(command = "make"))
        val wf = workflow("w", listOf(gate, notify, suggested, chosen, silent, agent, bare, check), name = "Ship")
        val body = compiled(inputs(listOf(wf)))
        val gateHook = body.hooks.first { it.nodeId == "g" }
        assertEquals("no action of this repository falls under this gate", gateHook.why)
        assertEquals(listOf("hook h-g (gate: ostrom-gate)"), body.nodes.first { it.id == "g" }.artifacts)
        val notes = body.notifications.associateBy { it.nodeId }
        assertEquals("log:ops", notes.getValue("n").channel)
        assertEquals("push done: {dev} ran \"{command}\" in {repo} (session {session}).", notes.getValue("n").message)
        assertEquals("log:team", notes.getValue("q").channel)
        assertEquals("slack:#x", notes.getValue("m").suggestedChannel?.channel)
        assertEquals(PackageMode.ENFORCE, notes.getValue("o").mode)
        val why = body.hooks.filter { it.kind == NOTIFY }.associate { it.nodeId to it.why }
        assertEquals("no channel chosen for this notification: logged only", why["n"])
        assertEquals("channel suggested by the analysis (slack:#x), not chosen by a person: logged only", why["m"])
        assertNull(why["o"])
        assertTrue("q" !in why)
        assertTrue(body.scripts.isNotEmpty() && body.hooks.none { it.nodeId == "c" })
        val files = body.files.associate { it.path to it.content }
        assertTrue(files.getValue("agents/ostrom-look.md").contains("\nRead it.\n"))
        assertTrue(files.getValue("agents/ostrom-bare.md").contains("\nBare\n"))
        val skill = files.getValue("skills/ostrom-wf-ship/SKILL.md")
        assertTrue(skill.contains("A human yes is needed from the production approver."), skill)
        assertTrue(skill.contains("Ostrom tells #rel by itself"), skill)
        assertTrue(skill.contains("Before the action, Ostrom runs `make`"), skill)
        assertTrue(skill.contains("(and task_name `ostrom_bare`). Otherwise do it yourself."), skill)
        val context = body.body.getValue("context").jsonPrimitive.content
        val line = "\n- Ship; skill ostrom-wf-ship; enforced here: gate \"Ship it\", notify \"push\""
        assertTrue(context.endsWith(line), context)
    }

    @Test
    fun everyOptionalFieldOfANodeCompilesSetOrNot() = runTest {
        // A check that leads to a gate without actions of its own takes the classes the catalog gives that gate.
        val gate = node(
            "g",
            GATE,
            label = "Approve the merge",
            detail = "Before main.",
            suggested = NodeSuggestions(actions = texts("merge")),
        )
        val linked = node("c", CHECK, label = "Lint", run = NodeRun(command = "make lint", where = RunLocation.OSTROM))
        // Its own command wins over the analysis'; a dismissed suggestion or no command at all compiles no script.
        val own = node(
            "k",
            CHECK,
            label = "Unit",
            run = NodeRun(command = "make test", on = NodeRunOn(listOf("merge", "commit", "unknown"))),
            suggested = NodeSuggestions(command = text("npm test")),
        )
        val empty = node("e", CHECK, label = "Empty", run = NodeRun(command = ""))
        val dismissed = node(
            "d",
            CHECK,
            label = "Dismissed",
            suggested = NodeSuggestions(command = text("npm t"), dismissed = listOf("command")),
        )
        val notify = node(
            "n",
            NOTIFY,
            label = "Merged",
            run = NodeRun(channel = "slack:#rel", on = NodeRunOn(listOf("merge"))),
            suggested = NodeSuggestions(channel = text("slack:#x")),
        )
        val agent = node("a", AGENT, label = "Review", run = NodeRun(instructions = "Read the diff."))
        val wf = workflow("w", listOf(linked, gate, own, empty, dismissed, notify, agent), edges = chain("c", "g"))
        val body = compiled(inputs(listOf(wf)))

        val trigger = body.nodes.first { it.id == "c" }.trigger
        assertEquals("gate", trigger?.from)
        assertEquals("g", trigger?.gateNodeId)
        val scripts = body.scripts.associateBy { it.nodeId }
        assertEquals(setOf("c", "k"), scripts.keys, "no command, no script")
        assertEquals(RunLocation.OSTROM, scripts.getValue("c").where)
        assertEquals(RunLocation.WORKSTATION, scripts.getValue("k").where)
        assertEquals("make test", scripts.getValue("k").command)
        assertNull(scripts.getValue("k").suggested)
        val unit = body.nodes.first { it.id == "k" }.trigger
        assertEquals(listOf("merge", "commit", "unknown"), unit?.classes)
        assertTrue("\\bgit\\s+commit\\b" in unit?.resolved.orEmpty(), "an extra class brings its patterns")
        val note = body.notifications.single()
        assertEquals("slack:#rel", note.channel)
        assertNull(note.suggestedChannel, "a channel chosen by a person hides the suggestion")
        val files = body.files.associate { it.path to it.content }
        assertTrue(files.getValue("agents/ostrom-review.md").contains("Read the diff."))
        val skill = files.getValue("skills/ostrom-wf-w/SKILL.md")
        assertTrue(skill.contains("\nBefore main.\n"), skill)
        assertTrue(skill.contains("\nRead the diff."), skill)
    }

    @Test
    fun anEmptyOrganizationHasAnEmptyContextAndSignsWithoutARepository() = runTest {
        val body = compiled(inputs(emptyList(), repo = null))
        assertEquals("", body.body.getValue("context").jsonPrimitive.content)
        val envelope = PackageSigner.sign(body, NOW, SeedSigner(hex(GoldenPackage.SEED_HEX)), pinned = false)
        assertNull(envelope.repo)
        assertEquals(false, envelope.pinned)
        assertEquals(body.contentDigest, envelope.contentDigest)
        assertTrue(envelope.version.startsWith("p202610300845-"))
    }
}
