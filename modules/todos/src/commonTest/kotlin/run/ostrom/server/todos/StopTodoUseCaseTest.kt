// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos

import run.ostrom.server.todos.domain.TodoCheckReport
import run.ostrom.server.todos.domain.TodoException
import run.ostrom.server.todos.domain.TodoHookEvent
import run.ostrom.server.todos.domain.TodoItemStatus
import run.ostrom.server.todos.domain.TodoListStatus
import run.ostrom.server.todos.domain.TodoStopAction
import run.ostrom.server.todos.domain.TodoStopDecision
import run.ostrom.server.todos.domain.TodoStopInput
import run.ostrom.server.todos.domain.TodoStopItemReport
import run.ostrom.server.todos.domain.TodoStopPhase
import run.ostrom.server.todos.fakes.ADA
import run.ostrom.server.todos.fakes.ORG_A
import run.ostrom.server.todos.fakes.World
import run.ostrom.server.todos.fakes.ada
import run.ostrom.server.todos.fakes.adaAgent
import run.ostrom.server.todos.fakes.item
import run.ostrom.server.todos.fakes.root
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The Stop hook's questions, from TeamCode's `TodoServiceTest`. */
class StopTodoUseCaseTest {
    private val world = World()
    private val t0 = Instant.parse("2026-11-13T14:02:00Z")

    private suspend fun created(vararg ids: String) =
        world.change.create(ada, "s1", "The plan", emptyList(), ids.map { item(it) })

    private suspend fun ask(
        phase: TodoStopPhase,
        vararg reports: TodoStopItemReport,
        orphans: List<String> = emptyList(),
    ) = world.stop(adaAgent, null, TodoStopInput(phase, items = reports.toList(), orphans = orphans))

    private fun passed(id: String) = TodoStopItemReport(id, listOf(TodoCheckReport(0, true, "merged")))
    private fun failed(id: String) = TodoStopItemReport(id, listOf(TodoCheckReport(0, false, "PR reverted")))

    private fun assertAllowed(why: String, decision: TodoStopDecision) {
        assertEquals(TodoStopAction.ALLOW to why, decision.action to decision.why)
    }

    @Test
    fun aSessionWithoutATodoStops() = runTest {
        val decision = ask(TodoStopPhase.BEGIN)
        assertAllowed("no_list", decision)
        assertNull(decision.list)
    }

    @Test
    fun aMemberWhoseHookIsOffStopsWithoutAnythingRunOrWritten() = runTest {
        created("a")
        world.settings.set(root, ADA, enabled = false)
        val changes = world.todos.changes
        assertAllowed("hook_disabled", ask(TodoStopPhase.BEGIN))
        assertAllowed("hook_disabled", ask(TodoStopPhase.VERIFY, passed("a")))
        assertEquals(changes, world.todos.changes, "nothing is written")
        assertNull(world.todos.list!!.lastHookAt)
        assertEquals(TodoItemStatus.TODO, world.todos.list!!.item("a")!!.status)
    }

    @Test
    fun aFinishedTodoLetsTheStopPass() = runTest {
        created("a")
        world.todos.list = world.todos.list!!.copy(status = TodoListStatus.DONE)
        assertAllowed("finished", ask(TodoStopPhase.BEGIN))
    }

    @Test
    fun aBackgroundAgentOrAnOpenWaitLetsTheStopPassAndTheVisitIsKept() = runTest {
        created("a")
        val background = world.stop(adaAgent, null, TodoStopInput(TodoStopPhase.BEGIN, background = true))
        assertAllowed("background_task", background)
        assertEquals(t0, background.list!!.lastHookAt)
        world.waits.open = true
        assertAllowed("wait_open", ask(TodoStopPhase.VERIFY, passed("a")))
        assertEquals(TodoItemStatus.TODO, world.todos.list!!.item("a")!!.status, "nothing is judged")
    }

    @Test
    fun beginAsksForTheChecksAndRecordsTheVisit() = runTest {
        created("a")
        val decision = ask(TodoStopPhase.BEGIN)
        assertEquals(TodoStopAction.VERIFY, decision.action)
        assertEquals(t0, decision.list!!.lastHookAt)
        assertEquals(t0, world.todos.list!!.lastHookAt)
    }

    @Test
    fun verifyTicksTheItemsWhoseChecksPassAndBlocksWhileItemsRemain() = runTest {
        created("a", "b")
        val decision = ask(TodoStopPhase.VERIFY, passed("a"), failed("b"), passed("ghost"))
        assertEquals(TodoStopAction.BLOCK, decision.action)
        assertEquals(TodoItemStatus.DONE, decision.list!!.item("a")!!.status)
        assertEquals(TodoItemStatus.TODO, decision.list!!.item("b")!!.status)
        assertTrue(decision.reason!!.startsWith("[Ostrom todo] 1 of 2 items done."))
        assertEquals(1, decision.list!!.stall)
        assertEquals(decision.list, world.todos.list)
    }

    @Test
    fun aDoneItemIsNotTickedAgainAndAnItemInProgressIs() = runTest {
        created("a", "b")
        ask(TodoStopPhase.VERIFY, passed("a"))
        world.change.start(adaAgent, null, "b", false, null)
        val decision = ask(TodoStopPhase.VERIFY, failed("a"), passed("b"))
        assertEquals(TodoStopAction.SETTLE, decision.action, "every item closed: the final verification")
        assertEquals(listOf(TodoItemStatus.DONE, TodoItemStatus.DONE), decision.list!!.items.map { it.status })
    }

    @Test
    fun settleFinishesTheTodoWhenTheChecksStillPass() = runTest {
        created("a")
        ask(TodoStopPhase.VERIFY, passed("a"))
        val decision = ask(TodoStopPhase.SETTLE, passed("a"), passed("ghost"))
        assertAllowed("settled", decision)
        assertEquals(TodoListStatus.DONE, world.todos.list!!.status)
    }

    @Test
    fun settleReopensAnItemThatNoLongerPasses() = runTest {
        created("a", "b")
        ask(TodoStopPhase.VERIFY, passed("a"), passed("b"))
        val decision = ask(TodoStopPhase.SETTLE, failed("a"), passed("b"))
        assertEquals(TodoStopAction.BLOCK, decision.action)
        assertEquals(TodoItemStatus.TODO, decision.list!!.item("a")!!.status)
        assertEquals("PR reverted", decision.list!!.item("a")!!.proof!!.text)
    }

    @Test
    fun settleBeforeEveryItemIsClosedChangesNothingButTheCounter() = runTest {
        created("a", "b")
        val decision = ask(TodoStopPhase.SETTLE, passed("a"), passed("b"))
        assertEquals(TodoStopAction.BLOCK, decision.action)
        assertEquals(listOf(TodoItemStatus.TODO, TodoItemStatus.TODO), decision.list!!.items.map { it.status })
    }

    @Test
    fun thePullRequestsTheSessionOpenedBecomeItems() = runTest {
        created("a")
        val decision = ask(TodoStopPhase.VERIFY, orphans = listOf("https://github.com/acme/x/pull/9"))
        assertEquals(listOf("a", "finish-pr-9"), decision.list!!.items.map { it.id })
    }

    @Test
    fun aMalformedStopIsRefused() = runTest {
        created("a")
        val repeated = assertFailsWith<TodoException> { ask(TodoStopPhase.VERIFY, passed("a"), passed("a")) }
        assertEquals("invalid_stop", repeated.type)
        val orphans = List(6) { "https://github.com/acme/x/pull/$it" }
        val refused = assertFailsWith<TodoException> { ask(TodoStopPhase.VERIFY, orphans = orphans) }
        assertEquals("invalid_stop", refused.type)
        val tooMany = Array(501) { TodoStopItemReport("i$it", emptyList()) }
        assertEquals("invalid_stop", assertFailsWith<TodoException> { ask(TodoStopPhase.VERIFY, *tooMany) }.type)
        val wrongIndex = TodoStopItemReport("a", listOf(TodoCheckReport(3, true, "ok")))
        assertEquals("invalid_proof", assertFailsWith<TodoException> { ask(TodoStopPhase.VERIFY, wrongIndex) }.type)
    }

    @Test
    fun theThirdStopWithoutProgressAsksTheOwnerAndLetsTheStopPass() = runTest {
        created("a", "b")
        assertEquals(TodoStopAction.BLOCK, ask(TodoStopPhase.VERIFY).action)
        assertEquals(TodoStopAction.BLOCK, ask(TodoStopPhase.VERIFY).action)
        val decision = world.stop(adaAgent, null, TodoStopInput(TodoStopPhase.VERIFY, lastMessage = "I am stuck."))
        assertAllowed("escalated", decision)
        assertTrue(decision.escalated)
        val asked = world.waits.asked.single()
        assertEquals("Blocked: Item a", asked.title)
        assertTrue(asked.body.contains("I am stuck."))
        assertEquals(true, asked.dropItem)
        val list = world.todos.list!!
        assertEquals(TodoItemStatus.DROP_REQUESTED, list.item("a")!!.status)
        assertEquals("blocked-1", list.item("a")!!.dropWaitId)
        assertEquals(0, list.stall, "the counter starts again")
        val event = TodoHookEvent(t0, TodoHookEvent.BLOCKED, "Blocked on a: a wait asks the owner.")
        assertEquals(event, list.hookEvents.single())
        assertEquals(decision.list, list)
    }

    @Test
    fun theItemInProgressIsTheStuckOneWhenNoneIsReady() = runTest {
        world.change.create(ada, "s1", "The plan", emptyList(), listOf(item("a"), item("b", deps = listOf("a"))))
        world.change.start(adaAgent, null, "a", false, null)
        repeat(3) { ask(TodoStopPhase.VERIFY) }
        assertEquals(TodoItemStatus.DROP_REQUESTED, world.todos.list!!.item("a")!!.status)
    }

    @Test
    fun aStuckTodoWithNoOpenItemAsksWithoutADrop() = runTest {
        created("a")
        world.todos.list = world.todos.list!!.let { list ->
            list.copy(items = list.items.map { it.copy(status = TodoItemStatus.DROP_REQUESTED, dropWaitId = "w") })
        }
        repeat(3) { ask(TodoStopPhase.VERIFY) }
        assertEquals(false, world.waits.asked.single().dropItem)
        assertEquals("Blocked on the todo: a wait asks the owner.", world.todos.list!!.hookEvents.single().text)
    }

    @Test
    fun anItemFinishedMeanwhileIsNotMarkedStuck() = runTest {
        created("a", "b")
        repeat(2) { ask(TodoStopPhase.VERIFY) }
        // Another replica finishes the stuck item between the judgement and the escalation's change.
        world.todos.races[world.todos.changes + 1] = { list ->
            list.copy(items = list.items.map { if (it.id == "a") it.copy(status = TodoItemStatus.DONE) else it })
        }
        assertAllowed("escalated", ask(TodoStopPhase.VERIFY))
        assertEquals(TodoItemStatus.DONE, world.todos.list!!.item("a")!!.status)
        assertNull(world.todos.list!!.item("a")!!.dropWaitId)
    }

    @Test
    fun anEscalationWhoseChangeFailsWithdrawsItsWait() = runTest {
        created("a")
        repeat(2) { ask(TodoStopPhase.VERIFY) }
        world.todos.races[world.todos.changes + 1] = { error("The database is gone.") }
        assertFailsWith<IllegalStateException> { ask(TodoStopPhase.VERIFY) }
        assertEquals(listOf("blocked-1"), world.waits.cancelled)
        assertFalse(world.todos.list!!.items.any { it.status == TodoItemStatus.DROP_REQUESTED })
        assertEquals(ORG_A, world.waits.asked.single().owner.orgId)
    }

    @Test
    fun theStoppingPhasesAreReadFromTheirWireNames() {
        assertEquals(
            listOf(TodoStopPhase.BEGIN, TodoStopPhase.VERIFY, TodoStopPhase.SETTLE, null),
            listOf("begin", "verify", "settle", "end").map { TodoStopPhase.fromWire(it) },
        )
    }
}
