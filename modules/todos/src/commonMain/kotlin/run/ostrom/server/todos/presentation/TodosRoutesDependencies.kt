// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos.presentation

import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.todos.domain.ChangeTodoUseCase
import run.ostrom.server.todos.domain.ReadTodoUseCase
import run.ostrom.server.todos.domain.StopHookSettingsUseCase
import run.ostrom.server.todos.domain.StopTodoUseCase

/**
 * What the todo routes need.
 *
 * @property read Reads a session's todo and its reminder block.
 * @property change Changes it.
 * @property stop Answers the Stop hook.
 * @property settings The per-member switch of the Stop hook.
 * @property callers Who a request acts for.
 * @property audit The organization's audit log, which a reset is written to.
 */
data class TodosRoutesDependencies(
    val read: ReadTodoUseCase,
    val change: ChangeTodoUseCase,
    val stop: StopTodoUseCase,
    val settings: StopHookSettingsUseCase,
    val callers: TodoCallers,
    val audit: OrgAuditTrail,
)
