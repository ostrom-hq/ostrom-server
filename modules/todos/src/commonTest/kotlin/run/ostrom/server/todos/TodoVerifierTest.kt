// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos

import run.ostrom.server.todos.domain.TodoCheck
import run.ostrom.server.todos.domain.TodoCheckReport
import run.ostrom.server.todos.domain.TodoCheckResult
import run.ostrom.server.todos.domain.TodoDoneWhen
import run.ostrom.server.todos.domain.TodoException
import run.ostrom.server.todos.domain.TodoItem
import run.ostrom.server.todos.domain.TodoItemStatus
import run.ostrom.server.todos.domain.TodoLink
import run.ostrom.server.todos.domain.TodoOrigin
import run.ostrom.server.todos.domain.TodoWaitState
import run.ostrom.server.todos.fakes.LONG
import run.ostrom.server.todos.fakes.ORG_A
import run.ostrom.server.todos.fakes.World
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Instant

/** What the server verifies itself, and what it takes from the hook's report. */
class TodoVerifierTest {
    private val world = World()
    private val verifier = world.verifier
    private val segments = listOf("s1", "s1~r2")

    private fun item(id: String, vararg checks: TodoCheck, links: List<TodoLink> = emptyList()) = TodoItem(
        id, "Item $id", LONG, TodoDoneWhen("done", checks.toList()), emptyList(), TodoOrigin.PLAN, null,
        TodoItemStatus.TODO, links = links,
    )

    private suspend fun seen(item: TodoItem): Map<TodoCheck, TodoCheckResult> =
        verifier.serverChecks(ORG_A, segments, listOf(item)).getValue(item.id)

    private suspend fun passed(item: TodoItem) = seen(item).values.map { it.passed }

    @Test
    fun nowIsCutToTheMillisecondAsStored() {
        world.clock.now = Instant.fromEpochMilliseconds(1_000).plus(1.microseconds)
        assertEquals(Instant.fromEpochMilliseconds(1_000), verifier.now())
    }

    @Test
    fun anArtifactIsPublishedAtTheVersionAsked() = runTest {
        world.artifacts.versions["art"] = 2
        val linked = listOf(TodoLink("artifact", "art"))
        assertEquals(listOf(false), passed(item("a", TodoCheck.ArtifactPublished())), "nothing is linked")
        assertEquals(listOf(true), passed(item("a", TodoCheck.ArtifactPublished(), links = linked)))
        assertEquals(listOf(true), passed(item("a", TodoCheck.ArtifactPublished("art", minVersion = 2))))
        assertEquals(listOf(false), passed(item("a", TodoCheck.ArtifactPublished("art", minVersion = 3))))
        val missing = seen(item("a", TodoCheck.ArtifactPublished("gone"))).values.single()
        assertEquals("Artifact gone is not published.", missing.observed)
        assertEquals(emptyMap(), seen(item("a", TodoCheck.PrMerged("acme/x"))), "the hook runs the other checks")
    }

    @Test
    fun aWaitOfTheSessionIsAnsweredWithTheActionAsked() = runTest {
        val waits = world.waits.states
        waits["w-open"] = TodoWaitState("open", null, "s1")
        waits["w-yes"] = TodoWaitState("resolved", "yes", "s1~r2")
        waits["w-other"] = TodoWaitState("resolved", "yes", "s2")
        fun answered(ref: String?, action: String? = null) =
            item("a", TodoCheck.WaitAnswered(action), links = listOfNotNull(ref?.let { TodoLink("wait", it) }))
        assertEquals(listOf(false), passed(answered(null)), "nothing is linked")
        assertEquals(listOf(false), passed(answered("w-unknown")))
        assertEquals(listOf(false), passed(answered("w-other")), "another session's wait")
        assertEquals(listOf(false), passed(answered("w-open")))
        assertEquals(listOf(false), passed(answered("w-yes", "approve")))
        assertEquals(listOf(true), passed(answered("w-yes", "yes")))
        assertEquals(listOf(true), passed(answered("w-yes")))
    }

    @Test
    fun theReportIsTakenByPositionAndNeverForWhatTheServerSees() = runTest {
        val pr = TodoCheck.PrMerged("acme/x")
        val art = TodoCheck.ArtifactPublished("art")
        val subject = item("a", pr, art)
        val observed = seen(subject)
        val results = verifier.results(
            subject,
            listOf(TodoCheckReport(0, true, "merged", listOf("https://x/1")), TodoCheckReport(1, true, "a lie")),
            observed,
        )
        assertEquals(listOf(TodoCheckResult(pr, true, "merged", listOf("https://x/1"))) + observed.values, results)
    }

    @Test
    fun aMalformedReportIsRefused() {
        val subject = item("a", TodoCheck.PrMerged("acme/x"))
        fun refused(vararg reports: TodoCheckReport) =
            assertFailsWith<TodoException> { verifier.results(subject, reports.toList(), emptyMap()) }.type
        assertEquals("invalid_proof", refused(TodoCheckReport(1, true, "ok")))
        assertEquals("invalid_proof", refused(TodoCheckReport(-1, true, "ok")))
        assertEquals("invalid_proof", refused(TodoCheckReport(0, true, "ok"), TodoCheckReport(0, true, "ok")))
        assertEquals("invalid_proof", refused(TodoCheckReport(0, true, "x".repeat(2_001))))
        assertEquals("invalid_proof", refused(TodoCheckReport(0, true, "ok", List(11) { "https://x/$it" })))
        assertEquals("invalid_proof", refused(TodoCheckReport(0, true, "ok", listOf("http://x/1"))))
        assertEquals("invalid_proof", refused(TodoCheckReport(0, true, "ok", listOf("https://" + "x".repeat(2_000)))))
    }
}
