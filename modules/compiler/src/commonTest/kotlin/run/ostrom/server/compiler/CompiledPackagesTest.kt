// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler

import run.ostrom.core.api.models.NodeRun
import run.ostrom.core.api.models.WorkflowNodeKind.CHECK
import run.ostrom.core.api.models.WorkflowNodeKind.GATE
import run.ostrom.core.api.models.WorkflowNodeKind.TRIGGER
import run.ostrom.server.compiler.domain.models.CompileInputs
import run.ostrom.server.compiler.domain.services.CompiledBody
import run.ostrom.server.compiler.domain.services.CompiledPackages
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class CompiledPackagesTest {
    private val org = Uuid.parse("00000000-0000-0000-0000-00000000000a")
    private val other = Uuid.parse("00000000-0000-0000-0000-00000000000b")

    private fun release(id: String, command: String = "make test") = workflow(
        id,
        listOf(
            node("t", TRIGGER, label = "push"),
            node("c", CHECK, label = "Tests", run = NodeRun(command = command, on = on("push-main").on)),
            node("g", GATE, label = "Push to main", approvers = listOf("lead")),
        ),
        chain("t", "c", "g"),
    )

    private val base = inputs(listOf(release("w1")))

    // A rule's gate, as the rules module hands it to the catalogue.
    private val gate = node("gate", GATE, approvers = listOf("mia"), actions = listOf(action("push-main")))

    // A rule whose stored JSON weighs about [chars] characters.
    private fun heavy(id: String, chars: Int) =
        rule(id).copy(raw = JsonObject(mapOf("statement" to JsonPrimitive("x".repeat(chars)))))

    private suspend fun CompiledPackages.compiling(orgId: Uuid, inputs: CompileInputs): CompiledBody =
        of(orgId, inputs) { compiled(inputs) }

    private suspend fun CompiledPackages.kept(orgId: Uuid, inputs: CompileInputs): CompiledBody =
        of(orgId, inputs) { error("compiled again") }

    @Test
    fun theSameInputsAskedAtOnceAreCompiledOnce() = runTest {
        val packages = CompiledPackages()
        val bodies = List(100) { n ->
            // The time is no input: each caller reads its own.
            async { packages.of(org, base.copy(now = NOW + n.seconds)) { yield(); compiled(base) } }
        }.awaitAll()

        assertEquals(1, packages.compilations)
        assertTrue(bodies.all { it === bodies.first() })
    }

    @Test
    fun anotherOrganizationWithTheSameInputsNeverReceivesTheFirstOnesPackage() = runTest {
        val packages = CompiledPackages()
        val first = packages.compiling(org, base)
        val theirs = packages.of(other, base) { compiled(base.copy(serverUrl = "https://other.example")) }

        assertEquals(2, packages.compilations)
        assertNotEquals(first.body, theirs.body)
        assertSame(first, packages.kept(org, base))
        assertSame(theirs, packages.kept(other, base))
    }

    @Test
    fun everyInputThatChangesIsCompiledAgainAndTheTimeAloneIsNot() = runTest {
        val packages = CompiledPackages()
        val first = packages.compiling(org, base)
        val changes = listOf(
            base.copy(repo = "acme-web"),
            base.copy(serverUrl = "https://acme.example/v2"),
            inputs(listOf(release("w1", command = "make check"))),
            inputs(listOf(release("w1"), release("w2"))),
            base.copy(rules = listOf(rule("r1"))),
            base.copy(rules = listOf(rule("r1", status = "rejected"))),
            base.copy(decisions = listOf(decision("w1", receipt = "rcpt_2"))),
            base.copy(repos = listOf("acme-api", "acme-web")),
            base.copy(prodApprover = "ops"),
            base.copy(codePublishers = listOf("dev-a")),
            base.copy(ruleGates = listOf(workflow("rule:r1", listOf(gate)))),
        )
        changes.forEachIndexed { n, changed ->
            val body = packages.compiling(org, changed)
            assertEquals(n + 2L, packages.compilations, "change $n compiles again")
            assertSame(body, packages.kept(org, changed.copy(now = NOW + 1.hours)))
        }

        assertSame(first, packages.kept(org, base.copy(now = NOW + 1.hours)))
    }

    @Test
    fun aCompilationThatFailsIsNotKeptAndTheNextCallerCompiles() = runTest {
        val packages = CompiledPackages()
        assertFailsWith<IllegalStateException> { packages.of(org, base) { error("unreadable") } }

        val body = packages.compiling(org, base)

        assertEquals(1, packages.compilations)
        assertSame(body, packages.kept(org, base))
    }

    @Test
    fun theLeastRecentlyUsedPackageGoesFirst() = runTest {
        val packages = CompiledPackages(maxEntries = 2)
        val a = base
        val b = base.copy(repo = "acme-web")
        val c = base.copy(repo = "acme-docs")
        packages.compiling(org, a)
        packages.compiling(org, b)
        packages.kept(org, a)
        packages.compiling(org, c)

        packages.kept(org, a)
        packages.kept(org, c)
        packages.compiling(org, b)
        assertEquals(4, packages.compilations)
    }

    @Test
    fun theWeightKeptStaysUnderItsBoundAndAPackageHeavierThanTheWholeLeavesTheOthers() = runTest {
        val light = base.copy(rules = listOf(heavy("r1", 10_000)))
        val heavier = base.copy(rules = listOf(heavy("r1", 30_000)))
        val weights = listOf(light, heavier).map { CompiledPackages().apply { compiling(org, it) }.kept }
        assertTrue(weights[0] in 20_000..<weights[1], "the weight follows the JSON: $weights")

        // Both do not fit: the older goes.
        val two = CompiledPackages(maxWeight = weights.sum() - 1)
        two.compiling(org, light)
        two.compiling(org, heavier)
        assertEquals(weights[1], two.kept)
        two.kept(org, heavier)
        two.compiling(org, light)
        assertEquals(3, two.compilations)

        // Heavier than the whole: served, not kept, and the lighter one stays.
        val one = CompiledPackages(maxWeight = weights[0])
        one.compiling(org, light)
        one.compiling(org, heavier)
        one.compiling(org, heavier)
        one.kept(org, light)
        assertEquals(weights[0], one.kept)
        assertEquals(3, one.compilations)
    }

    @Test
    fun aPackagePushedOutWhileItCompiledIsServedButNotPutBack() = runTest {
        val packages = CompiledPackages(maxEntries = 1)
        val gate = CompletableDeferred<Unit>()
        val slow = async { packages.of(org, base) { gate.await(); compiled(base) } }
        runCurrent()
        val web = base.copy(repo = "acme-web")
        packages.compiling(org, web)
        gate.complete(Unit)

        assertEquals("acme-api", slow.await().repo)
        packages.kept(org, web)
        packages.compiling(org, base)
        assertEquals(3, packages.compilations)
    }
}
