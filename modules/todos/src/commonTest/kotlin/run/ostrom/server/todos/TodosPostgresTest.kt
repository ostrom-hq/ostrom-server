// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos

import run.ostrom.server.testing.serverTest
import run.ostrom.server.todos.domain.TodoCheck
import run.ostrom.server.todos.domain.TodoCheckResult
import run.ostrom.server.todos.domain.TodoException
import run.ostrom.server.todos.domain.TodoHookEvent
import run.ostrom.server.todos.domain.TodoItemStatus
import run.ostrom.server.todos.domain.TodoLink
import run.ostrom.server.todos.domain.TodoList
import run.ostrom.server.todos.domain.TodoProof
import run.ostrom.server.todos.domain.TodoRef
import run.ostrom.server.todos.domain.TodoRules
import run.ostrom.server.todos.domain.TodosRepository
import run.ostrom.server.todos.fakes.ADA
import run.ostrom.server.todos.fakes.BEN
import run.ostrom.server.todos.fakes.ORG_A
import run.ostrom.server.todos.fakes.ORG_B
import run.ostrom.server.todos.fakes.item
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The todo tables on a real PostgreSQL: a list reads back as written, once per session, within its organization. */
class TodosPostgresTest {
    private val t0 = Instant.parse("2026-11-13T14:02:00.123Z")

    private fun list(org: Uuid = ORG_A, session: String = "s1") = TodoRules.create(
        Uuid.random(),
        org,
        session,
        "The plan",
        listOf(TodoRef("artifact", id = "plan-1", title = "Plan")),
        listOf(item("a"), item("b", TodoCheck.Attested("seen"), deps = listOf("a"))),
        t0,
    )

    @Test
    fun aListReadsBackAsWrittenAndChangesInPlace() = serverTest(listOf(TodosModule)) { server ->
        val repository = server.koin.get<TodosRepository>()
        val written = list()
        assertTrue(repository.create(written))
        assertFalse(repository.create(list()), "one list per session")
        assertEquals(written, repository.find(ORG_A, "s1"))
        assertNull(repository.find(ORG_A, "s2"))

        val proof = TodoProof(t0, true, "merged", listOf("https://github.com/acme/x/pull/1"))
        val changed = repository.mutate(ORG_A, "s1") { current ->
            var next = TodoRules.start(current, "a", false, null, t0)
            next = TodoRules.note(next, "a", "Halfway", t0)
            next = TodoRules.link(next, "a", TodoLink("pr", "https://github.com/acme/x/pull/1", 1), false, t0)
            next = TodoRules.add(next, item("c"), "Found on the way")
            next = TodoRules.recordEvent(next, TodoHookEvent.HOOK_ERROR, "gh failed", t0)
            next = TodoRules.stop(next).list.copy(lastHookAt = t0, updatedAt = t0)
            next = next.copy(items = next.items.map { if (it.id == "b") it.copy(proof = proof) else it })
            next to next
        }
        assertEquals(changed, repository.find(ORG_A, "s1"))
        assertEquals(listOf("a", "c", "b"), changed.items.map { it.id }, "the order is kept")

        val unchanged: TodoList = repository.mutate(ORG_A, "s1") { it to it }
        assertEquals(changed, unchanged)
        val done = repository.mutate(ORG_A, "s1") { current ->
            val results = current.item("a")!!.doneWhen.checks.map { TodoCheckResult(it, true, "merged") }
            val next = TodoRules.complete(current, "a", results, t0).list
            next to next.item("a")!!.status
        }
        assertEquals(TodoItemStatus.DONE, done)
        assertEquals(TodoItemStatus.DONE, repository.find(ORG_A, "s1")!!.item("a")!!.status)
    }

    @Test
    fun anotherOrganizationNeitherReadsNorChangesAList() = serverTest(listOf(TodosModule)) { server ->
        val repository = server.koin.get<TodosRepository>()
        val written = list()
        repository.create(written)
        assertNull(repository.find(ORG_B, "s1"))
        val refused = assertFailsWith<TodoException> { repository.mutate(ORG_B, "s1") { it to it } }
        assertEquals("todo_not_found", refused.type)
        assertTrue(repository.create(list(org = ORG_B)), "the same session id in another organization is its own")
        assertEquals(written, repository.find(ORG_A, "s1"))
    }

    @Test
    fun theStopHookIsOnUntilAnAdministratorTurnsItOff() = serverTest(listOf(TodosModule)) { server ->
        val repository = server.koin.get<TodosRepository>()
        assertTrue(repository.stopHookEnabled(ORG_A, ADA))
        repository.setStopHookEnabled(ORG_A, ADA, false)
        repository.setStopHookEnabled(ORG_A, BEN, false)
        repository.setStopHookEnabled(ORG_A, BEN, true)
        assertFalse(repository.stopHookEnabled(ORG_A, ADA))
        assertTrue(repository.stopHookEnabled(ORG_A, BEN))
        assertEquals(setOf(ADA), repository.stopHookDisabled(ORG_A))
        assertTrue(repository.stopHookEnabled(ORG_B, ADA), "a switch is per organization")
        assertEquals(emptySet(), repository.stopHookDisabled(ORG_B))
    }
}
