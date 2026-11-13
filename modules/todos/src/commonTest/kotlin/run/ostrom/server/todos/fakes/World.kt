// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos.fakes

import run.ostrom.server.todos.domain.ChangeTodoUseCaseImpl
import run.ostrom.server.todos.domain.ReadTodoUseCaseImpl
import run.ostrom.server.todos.domain.StopHookSettingsUseCaseImpl
import run.ostrom.server.todos.domain.StopTodoUseCaseImpl
import run.ostrom.server.todos.domain.TodoAccess
import run.ostrom.server.todos.domain.TodoVerifier

/** The todo use cases over the fakes. */
class World {
    val clock = TestClock()
    val todos = InMemoryTodos()
    val waits = FakeWaits()
    val artifacts = FakeArtifacts()
    val access = TodoAccess(FakeSessions())
    val verifier = TodoVerifier(todos, waits, artifacts, clock)
    val change = ChangeTodoUseCaseImpl(access, todos, verifier, waits)
    val read = ReadTodoUseCaseImpl(access, verifier)
    val stop = StopTodoUseCaseImpl(access, todos, verifier, waits)
    val settings = StopHookSettingsUseCaseImpl(todos, FakeMembers())
}
