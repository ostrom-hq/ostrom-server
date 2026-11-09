// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.runners.domain.CloudSession
import run.ostrom.server.runners.domain.ExecutionSpec
import run.ostrom.server.runners.domain.ExecutionStatus
import run.ostrom.server.runners.domain.RuntimeDriver

/**
 * The runtime of a server with no cloud runtime configured (`OSTROM_RUNNERS_DRIVER` unset): nothing starts, so a
 * session's start fails with `start_failed` after the grace delay, and stopping or deleting always succeeds.
 */
class DisabledRuntimeDriver : RuntimeDriver {
    override suspend fun ensureWorkspace(session: CloudSession) = Unit

    override suspend fun startExecution(spec: ExecutionSpec) = Unit

    override suspend fun executionStatus(session: CloudSession): ExecutionStatus = ExecutionStatus.FAILED

    override suspend fun stopExecutions(session: CloudSession) = Unit

    override suspend fun deleteWorkspace(session: CloudSession): Boolean = true
}
