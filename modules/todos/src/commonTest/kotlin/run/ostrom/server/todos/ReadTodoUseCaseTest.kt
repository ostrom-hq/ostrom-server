// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos

import run.ostrom.server.todos.domain.TodoAdminRequiredException
import run.ostrom.server.todos.domain.TodoCaller
import run.ostrom.server.todos.domain.TodoListStatus
import run.ostrom.server.todos.domain.TodoMemberNotFoundException
import run.ostrom.server.todos.domain.TodoSessionNotFoundException
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Who reads a session's todo, and the blocks a session is reminded of; who turns the Stop hook off. */
class ReadTodoUseCaseTest {
    private val world = World()
    private val read = world.read

    @Test
    fun thePersonTheirAgentAndAnAdministratorReadTheTodoNobodyElse() = runTest {
        val list = world.change.create(ada, "s1", "The plan", emptyList(), listOf(item("a")))
        assertEquals(list, read.get(ada, "s1~r2"))
        assertEquals(list, read.get(adaAgent, "s2"), "an agent token reads its own session whatever it names")
        assertEquals(list, read.get(root, "s1"))
        assertFailsWith<TodoSessionNotFoundException> { read.get(ben, "s1") }
        assertFailsWith<TodoSessionNotFoundException> { read.context(ben, "s1") }
        assertFailsWith<TodoSessionNotFoundException> { read.get(root.copy(orgId = ORG_B), "s1") }
        assertNull(read.get(ben, "s2"), "Ben's session has no todo")
    }

    @Test
    fun theContextIsTheActiveTodoOnly() = runTest {
        assertNull(read.context(ada, "s1"))
        world.change.create(ada, "s1", "The plan", emptyList(), listOf(item("a")))
        assertTrue(read.context(adaAgent, null)!!.startsWith("[Ostrom todo] Your todo: 0 of 1 items done."))
        assertEquals(read.context(ada, "s1"), read.wakeBlock(ORG_A, "s1"))
        world.todos.list = world.todos.list!!.copy(status = TodoListStatus.DONE)
        assertNull(read.wakeBlock(ORG_A, "s1"), "a finished todo adds nothing to a wake message")
        assertNull(read.wakeBlock(ORG_A, "s2"))
    }

    @Test
    fun onlyAnAdministratorWhoSignedInTurnsAMembersStopHookOff() = runTest {
        val settings = world.settings
        assertFailsWith<TodoAdminRequiredException> { settings.set(ada, BEN, false) }
        assertFailsWith<TodoAdminRequiredException> { settings.disabled(ada) }
        val adminAgent = TodoCaller(ORG_A, ADA, admin = true, agentSession = "s1~r2")
        assertFailsWith<TodoAdminRequiredException> { settings.set(adminAgent, BEN, false) }
        val stranger = "00000000-0000-0000-0000-000000000999"
        assertFailsWith<TodoMemberNotFoundException> { settings.set(root, stranger, false) }
        settings.set(root, BEN, false)
        settings.set(root, ADA, false)
        assertEquals(setOf(ADA, BEN), settings.disabled(root))
        settings.set(root, ADA, true)
        assertEquals(setOf(BEN), settings.disabled(root))
        assertEquals(emptySet(), settings.disabled(root.copy(orgId = ORG_B)))
    }
}
