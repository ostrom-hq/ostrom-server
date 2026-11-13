// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos

import run.ostrom.server.todos.domain.TodoCheck
import run.ostrom.server.todos.domain.TodoItem
import run.ostrom.server.todos.domain.TodoItemStatus
import run.ostrom.server.todos.domain.TodoLink
import run.ostrom.server.todos.domain.TodoList
import run.ostrom.server.todos.domain.TodoListStatus
import run.ostrom.server.todos.domain.TodoMessages
import run.ostrom.server.todos.domain.TodoProof
import run.ostrom.server.todos.domain.TodoRules
import run.ostrom.server.todos.fakes.ORG_A
import run.ostrom.server.todos.fakes.item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The messages the agent reads about its todo, from TeamCode's `TodoMessagesTest`. */
class TodoMessagesTest {
    private val t0 = Instant.parse("2026-11-13T14:02:00Z")

    private fun list(vararg ids: String) = TodoRules.create(
        Uuid.random(),
        ORG_A,
        "s1",
        "Ship the export.\nSee the artifact plan-1.",
        emptyList(),
        ids.map { item(it) },
        t0,
    )

    private fun TodoList.with(id: String, change: (TodoItem) -> TodoItem) =
        copy(items = items.map { if (it.id == id) change(it) else it })

    /** A line of the item list. */
    private fun row(mark: String, id: String, detail: String) =
        "  $mark ${id.padEnd(22)} Item $id" + if (detail.isEmpty()) "\n" else "   $detail"

    @Test
    fun theResumeMessageGivesTheReferenceTheNextItemInFullAndTheWholeList() {
        val proof = TodoProof(t0, false, "PR #3 is open, 2 checks failing")
        val l = list("a", "b").with("a") { it.copy(proof = proof) }
        val text = TodoMessages.resume(l)
        assertTrue(text.startsWith("[Ostrom todo] 0 of 2 items done. Items remain and no wait is open"), text)
        assertTrue(text.contains("Plan reference:\n  Ship the export.\n  See the artifact plan-1."), text)
        assertTrue(text.contains("Next item: a — Item a\n  Do this item completely"), text)
        assertTrue(text.contains("  Done when: done\n  Last check (14:02): PR #3 is open, 2 checks failing"), text)
        assertTrue(text.contains("  → a "), text)
        assertTrue(text.contains("  · b "), text)
        assertTrue(text.endsWith("todo_drop. Each of these ends the turn properly."), text)
    }

    @Test
    fun eachItemShowsWhereItIs() {
        val pr = TodoLink("pr", "https://github.com/acme/x/pull/3", 3)
        val release = TodoLink("release", "https://github.com/acme/x/releases/tag/v1.0.0")
        var l = list("merged", "released", "bare", "dropped", "asked", "attested", "doing", "waiting")
        l = l.with("merged") { it.copy(status = TodoItemStatus.DONE, links = listOf(pr)) }
            .with("released") { it.copy(status = TodoItemStatus.DONE, links = listOf(pr.copy(number = null), release)) }
            .with("bare") { it.copy(status = TodoItemStatus.DONE) }
            .with("dropped") { it.copy(status = TodoItemStatus.DROPPED) }
            .with("asked") { it.copy(status = TodoItemStatus.DROP_REQUESTED) }
            .with("attested") { it.copy(doneWhen = it.doneWhen.copy(checks = listOf(TodoCheck.Attested("x")))) }
            .with("doing") { it.copy(status = TodoItemStatus.DOING, name = "N".repeat(70)) }
        l = TodoRules.addOrphan(l, "https://github.com/acme/x/pull/9", t0)
        val text = TodoMessages.context(l)
        assertTrue(text.startsWith("[Ostrom todo] Your todo: 3 of 9 items done.\n"), text)
        assertTrue(text.contains(row("✓", "merged", "PR #3 merged")), text)
        assertTrue(text.contains(row("✓", "released", "pr https://github.com/acme/x/pull/3")), text)
        assertTrue(text.contains(row("✓", "bare", "")), text)
        assertTrue(text.contains(row("✗", "dropped", "dropped by the owner")), text)
        assertTrue(text.contains(row("?", "asked", "drop requested, waiting for the owner")), text)
        assertTrue(text.contains(row("·", "attested", "(attested)")), text)
        assertTrue(text.contains("  → ${"doing".padEnd(22)} ${"N".repeat(59)}…"), "the item in progress is next")
        assertTrue(text.contains("Next item: doing"), text)
        assertTrue(text.contains("(opened in this session, not covered by an item)"), text)
        assertFalse(text.contains("Do the next item now"), "the context gives no instruction")
    }

    @Test
    fun aTodoWithNothingReadyOrNothingOpenSaysSo() {
        val waiting = list("a").with("a") { it.copy(status = TodoItemStatus.DROP_REQUESTED) }
        assertTrue(TodoMessages.resume(waiting).contains("No item is ready: an item waits"))
        val closed = list("a").with("a") { it.copy(status = TodoItemStatus.DONE) }
        assertTrue(TodoMessages.resume(closed).contains("No item remains open."))
        val finished = closed.copy(status = TodoListStatus.DONE)
        val text = TodoMessages.context(finished)
        assertTrue(text.startsWith("[Ostrom todo] Your todo: 1 of 1 items done (done)."), text)
        assertTrue(text.contains("No item remains open."), text)
    }

    @Test
    fun aLongTodoKeepsTheReferenceAndTheNextItemAndShortensTheList() {
        val l = list(*(1..200).map { "item-$it" }.toTypedArray())
        val text = TodoMessages.resume(l)
        assertTrue(text.length <= TodoMessages.MAX_CHARS)
        assertTrue(text.contains("Plan reference:\n  Ship the export."))
        assertTrue(text.contains("Next item: item-1"))
        assertTrue(text.contains("\n  · item-200"), "every item keeps its line, without its name")
        val huge = l.copy(reference = "r".repeat(9_000))
        assertEquals(TodoMessages.MAX_CHARS, TodoMessages.resume(huge).length)
    }

    @Test
    fun theOwnerIsAskedWithTheListAndTheAgentsLastMessage() {
        val l = list("a", "b").with("a") { it.copy(status = TodoItemStatus.DONE) }
        assertEquals("Blocked: Item b", TodoMessages.blockedTitle(l))
        val body = TodoMessages.blockedBody(l, "I could not\nreach the CI.")
        assertTrue(body.startsWith("The agent stopped three times in a row"), body)
        assertTrue(body.contains("1 of 2 items done:\n- ✓ `a` Item a\n- → `b` Item b\n"), body)
        assertTrue(body.contains("Its last message:\n> I could not reach the CI."), body)
        assertFalse(TodoMessages.blockedBody(l, " ").contains("Its last message"))
        assertFalse(TodoMessages.blockedBody(l, null).contains("Its last message"))
        val stuck = l.with("b") { it.copy(status = TodoItemStatus.DROP_REQUESTED) }
        assertEquals("Blocked: the todo", TodoMessages.blockedTitle(stuck))
    }
}
