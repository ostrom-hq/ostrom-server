// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos

import run.ostrom.server.todos.domain.TodoCaller
import run.ostrom.server.todos.domain.TodoCheck
import run.ostrom.server.todos.domain.TodoCheckReport
import run.ostrom.server.todos.domain.TodoException
import run.ostrom.server.todos.domain.TodoHookEvent
import run.ostrom.server.todos.domain.TodoItemStatus
import run.ostrom.server.todos.domain.TodoLink
import run.ostrom.server.todos.domain.TodoListStatus
import run.ostrom.server.todos.domain.TodoOwner
import run.ostrom.server.todos.domain.TodoRef
import run.ostrom.server.todos.domain.TodoSessionNotFoundException
import run.ostrom.server.todos.domain.TodoWaitState
import run.ostrom.server.todos.domain.TodoWriteForbiddenException
import run.ostrom.server.todos.domain.withdrawingOnFailure
import run.ostrom.server.todos.fakes.ADA
import run.ostrom.server.todos.fakes.BEN
import run.ostrom.server.todos.fakes.ORG_A
import run.ostrom.server.todos.fakes.ORG_B
import run.ostrom.server.todos.fakes.World
import run.ostrom.server.todos.fakes.ada
import run.ostrom.server.todos.fakes.adaAgent
import run.ostrom.server.todos.fakes.ben
import run.ostrom.server.todos.fakes.item
import run.ostrom.server.todos.fakes.root
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ChangeTodoUseCaseTest {
    private val world = World()
    private val change = world.change
    private val t0 = Instant.parse("2026-11-13T14:02:00Z")

    private suspend fun created(vararg ids: String) =
        change.create(ada, "s1", "The plan", emptyList(), ids.map { item(it) })

    private suspend fun code(block: suspend () -> Unit) = assertFailsWith<TodoException> { block() }.type

    @Test
    fun theSessionsPersonCreatesItsTodoOnceKeyedOnTheRootSession() = runTest {
        val refs = listOf(TodoRef("artifact", id = "plan-1"))
        val list = change.create(ada, "s1~r2", "The plan", refs, listOf(item("a")))
        assertEquals("s1", list.sessionId, "a resumed session keeps one list")
        assertEquals(ORG_A, list.orgId)
        assertEquals(t0, list.createdAt)
        assertEquals(refs, world.todos.list?.refs)
        assertEquals("todo_exists", code { change.create(adaAgent, null, "Again", emptyList(), listOf(item("b"))) })
        assertEquals(listOf("a"), world.todos.list?.items?.map { it.id }, "the first list is kept")
    }

    @Test
    fun aClosedTodoIsResetAndANewOneStartsFromNothing() = runTest {
        assertEquals("todo_not_found", code { change.reset(ada, "s1") }, "nothing to reset")
        created("a")
        assertEquals("todo_active", code { change.reset(adaAgent, "s1") })
        assertEquals(TodoItemStatus.TODO, world.todos.list!!.item("a")!!.status, "the refusal changed nothing")
        assertFailsWith<TodoWriteForbiddenException> { change.reset(root, "s1") }
        assertFailsWith<TodoSessionNotFoundException> { change.reset(ben, "s1") }
        world.todos.list = world.todos.list!!.copy(status = TodoListStatus.DONE)
        assertEquals("todo_closed", code { change.add(ada, "s1", item("b"), "More work") }, "the dead end it opens")
        val cleared = change.reset(adaAgent, "s1~r2")
        assertEquals(listOf("a"), cleared.items.map { it.id })
        assertNull(world.todos.list, "as if the session never had a todo")
        val second = change.create(ada, "s1", "Second task", emptyList(), listOf(item("b")))
        assertEquals(listOf("b"), second.items.map { it.id })
    }

    @Test
    fun anAgentTokenActsInItsOwnSessionWhateverItNames() = runTest {
        val list = change.create(adaAgent, "s2", "The plan", emptyList(), listOf(item("a")))
        assertEquals("s1", list.sessionId)
        val strangerAgent = TodoCaller(ORG_A, BEN, admin = false, agentSession = "s1~r2")
        assertEquals("s1", change.note(strangerAgent, null, "a", "From the agent of the launch").sessionId)
    }

    @Test
    fun nobodyElseChangesTheTodoNotEvenAnAdministrator() = runTest {
        created("a")
        assertFailsWith<TodoSessionNotFoundException> { change.note(ben, "s1", "a", "x") }
        assertFailsWith<TodoWriteForbiddenException> { change.note(root, "s1", "a", "x") }
        assertFailsWith<TodoSessionNotFoundException> { change.note(ada, "ghost", "a", "x") }
        assertFailsWith<TodoSessionNotFoundException> { change.note(ada.copy(orgId = ORG_B), "s1", "a", "x") }
        assertEquals("invalid_request", code { change.note(ada, null, "a", "x") })
        assertTrue(world.todos.list!!.items.single().notes.isEmpty())
    }

    @Test
    fun aChangeWithoutAListIsNotFound() = runTest {
        assertEquals("todo_not_found", code { change.note(ada, "s1", "a", "x") })
        assertEquals("todo_not_found", code { change.complete(ada, "s1", "a", emptyList()) })
        assertEquals("todo_not_found", code { change.requestDrop(ada, "s1", "a", "why") })
    }

    @Test
    fun itemsAreAddedStartedNotedLinkedAndTheHookErrorsKept() = runTest {
        created("a", "b")
        change.add(adaAgent, null, item("c"), "Found a migration")
        val started = change.start(adaAgent, null, "b", background = true, agent = "x".repeat(100))
        assertEquals("<!-- ostrom-todo: ${started.list.id.toString().take(8)}/b -->", started.marker)
        assertEquals(TodoItemStatus.DOING, started.list.item("b")!!.status)
        assertTrue(started.list.item("b")!!.notes.single().text.contains("x".repeat(80) + "."), "the name is bounded")
        assertEquals(TodoItemStatus.DOING, change.start(adaAgent, null, "a", false, null).list.item("a")!!.status)
        change.note(adaAgent, null, "a", "Halfway")
        val pr = TodoLink("pr", "https://github.com/acme/x/pull/3", 3)
        val linked = change.link(adaAgent, null, "a", pr, false)
        assertEquals(pr.copy(at = t0), linked.item("a")!!.links.single())
        val failed = change.hookError(adaAgent, null, "gh: not found")
        assertEquals(listOf(TodoHookEvent(t0, TodoHookEvent.HOOK_ERROR, "gh: not found")), failed.hookEvents)
        assertEquals(listOf("a", "b", "c"), failed.items.map { it.id })
        assertEquals("Halfway", world.todos.list!!.item("a")!!.notes.single().text)
    }

    @Test
    fun anItemIsDoneOnlyOnItsChecksAndTheServerVerifiesWhatItCan() = runTest {
        val checked = item("a", TodoCheck.PrMerged("acme/x"), TodoCheck.ArtifactPublished("art"))
        change.create(ada, "s1", "The plan", emptyList(), listOf(checked))
        val reports = listOf(
            TodoCheckReport(0, true, "merged", listOf("https://github.com/acme/x/pull/3")),
            TodoCheckReport(1, true, "I published it, trust me"),
        )
        assertEquals("not_done", code { change.complete(adaAgent, null, "a", reports) })
        val kept = world.todos.list!!.item("a")!!
        assertEquals(TodoItemStatus.TODO, kept.status)
        assertEquals(false, kept.proof!!.passed, "the refusal keeps what was seen")
        assertTrue(kept.proof!!.text.contains("Artifact art is not published"))
        world.artifacts.versions["art"] = 1
        val done = change.complete(adaAgent, null, "a", reports)
        assertEquals(TodoItemStatus.DONE, done.item("a")!!.status)
        assertEquals(done, world.todos.list)
        assertEquals("unknown_item", code { change.complete(adaAgent, null, "ghost", emptyList()) })
    }

    @Test
    fun aDropAsksTheOwnerAndWaitsForTheAnswer() = runTest {
        created("a", "b")
        val list = change.requestDrop(adaAgent, null, "b", "No longer needed")
        assertEquals(TodoItemStatus.DROP_REQUESTED, list.item("b")!!.status)
        assertEquals("drop-1", list.item("b")!!.dropWaitId)
        val asked = world.waits.asked.single()
        assertEquals(TodoOwner(ORG_A, ADA, "s1~r2", listOf("s1", "s1~r2")), asked.owner)
        assertEquals("b" to "No longer needed", asked.title to asked.body)
        assertEquals("drop_pending", code { change.requestDrop(ada, "s1", "b", "again") })
        assertEquals(1, world.waits.asked.size, "a refused drop opens no wait")
        assertEquals("unknown_item", code { change.requestDrop(ada, "s1", "ghost", "why") })
        assertEquals("invalid_todo", code { change.requestDrop(ada, "s1", "a", " ") })
        assertEquals(1, world.waits.asked.size)
    }

    @Test
    fun aDropWhoseChangeFailsWithdrawsItsWait() = runTest {
        created("a")
        world.todos.failNextChange = true
        assertFailsWith<IllegalStateException> { change.requestDrop(ada, "s1", "a", "No longer needed") }
        assertEquals(listOf("drop-1"), world.waits.cancelled)
        assertEquals(TodoItemStatus.TODO, world.todos.list!!.item("a")!!.status)
    }

    @Test
    fun aWaitThatCannotBeWithdrawnLeavesTheFirstFailure() = runTest {
        created("a")
        world.todos.failNextChange = true
        world.waits.cancelFailure = IllegalStateException("The wait is gone.")
        val failure = assertFailsWith<IllegalStateException> { change.requestDrop(ada, "s1", "a", "why") }
        assertEquals("The database is gone.", failure.message)
    }

    @Test
    fun aCancelledWithdrawalStillCancels() = runTest {
        created("a")
        world.todos.failNextChange = true
        world.waits.cancelFailure = CancellationException("Shutting down.")
        assertFailsWith<CancellationException> { change.requestDrop(ada, "s1", "a", "why") }
    }

    @Test
    fun aCancelledChangeWithdrawsItsWait() = runTest {
        val owner = TodoOwner(ORG_A, ADA, "s1", listOf("s1"))
        assertFailsWith<CancellationException> {
            withdrawingOnFailure<Unit>(world.waits, owner, "w-1") { throw CancellationException("Cancelled.") }
        }
        assertEquals(listOf("w-1"), world.waits.cancelled)
        assertEquals("ok", withdrawingOnFailure(world.waits, owner, "w-2") { "ok" })
        assertEquals(listOf("w-1"), world.waits.cancelled, "a stored change keeps its wait")
    }

    @Test
    fun theOwnersAnswerToADropIsAppliedOnTheNextRead() = runTest {
        created("a", "b", "c")
        change.requestDrop(ada, "s1", "a", "why")
        change.requestDrop(ada, "s1", "b", "why")
        change.requestDrop(ada, "s1", "c", "why")
        world.waits.states["drop-1"] = waitState("resolved", "yes")
        world.waits.states["drop-2"] = waitState("resolved", "no")
        world.waits.states["drop-3"] = waitState("open", null)
        val read = assertNotNull(world.read.get(ada, "s1"))
        assertEquals(
            listOf(TodoItemStatus.DROPPED, TodoItemStatus.TODO, TodoItemStatus.DROP_REQUESTED),
            read.items.map { it.status },
        )
        assertNull(read.item("b")!!.dropWaitId)
        world.waits.states["drop-3"] = waitState("cancelled", null)
        assertEquals(TodoItemStatus.TODO, world.read.get(ada, "s1")!!.item("c")!!.status, "a cancelled wait keeps it")
    }

    @Test
    fun aDropRequestWithoutAWaitOrStillOpenIsLeftAsItIs() = runTest {
        created("a", "b", "c")
        val waits = listOf(null, "w1", "w2")
        world.todos.list = world.todos.list!!.let { list ->
            list.copy(
                items = list.items.mapIndexed { i, item ->
                    item.copy(status = TodoItemStatus.DROP_REQUESTED, dropWaitId = waits[i])
                },
            )
        }
        world.waits.states["w2"] = waitState("open", null)
        val changes = world.todos.changes
        assertEquals(world.todos.list, world.read.get(ada, "s1"))
        assertEquals(changes, world.todos.changes, "nothing answered, nothing written")
        world.waits.states["w1"] = waitState("resolved", "yes")
        val read = world.read.get(ada, "s1")!!
        assertEquals(
            listOf(TodoItemStatus.DROP_REQUESTED, TodoItemStatus.DROPPED, TodoItemStatus.DROP_REQUESTED),
            read.items.map { it.status },
        )
    }

    private fun waitState(status: String, action: String?) = TodoWaitState(status, action, "s1~r2")
}
