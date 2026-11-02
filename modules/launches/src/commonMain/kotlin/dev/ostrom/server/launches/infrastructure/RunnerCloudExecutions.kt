// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.infrastructure

import dev.ostrom.server.launches.domain.services.CloudExecutions
import dev.ostrom.server.runners.domain.CloudSessionsRepository
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [CloudExecutions] on the runners module's sessions: an execution is current while the session holds it (creating,
 * running, pausing or resuming) and it started before the launch was opened. A paused, deleted or failed session holds
 * none; a resumed one holds a newer execution, whose runner opens its own launch.
 *
 * @param sessions The cloud sessions.
 */
class RunnerCloudExecutions(private val sessions: CloudSessionsRepository) : CloudExecutions {
    override suspend fun current(orgId: Uuid, cloudSessionId: Uuid, openedAt: Instant): Boolean {
        val session = sessions.get(orgId, cloudSessionId) ?: return false
        val started = session.executionStartedAt ?: return false
        return session.state.holdsExecution && started <= openedAt
    }
}
