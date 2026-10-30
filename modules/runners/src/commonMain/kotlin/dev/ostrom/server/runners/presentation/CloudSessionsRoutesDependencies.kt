// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.presentation

import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.runners.domain.AuthenticateRunnerUseCase
import dev.ostrom.server.runners.domain.ChangeCloudSessionUseCase
import dev.ostrom.server.runners.domain.CreateCloudSessionUseCase
import dev.ostrom.server.runners.domain.GetCloudSessionUseCase
import dev.ostrom.server.runners.domain.ListCloudSessionsUseCase
import dev.ostrom.server.runners.domain.RunnerHeartbeatUseCase

/**
 * What the cloud session routes need.
 *
 * @property createCloudSession Creates a session.
 * @property getCloudSession Reads one.
 * @property listCloudSessions Lists the caller's.
 * @property changeCloudSession Pauses, resumes, deletes.
 * @property authenticateRunner Checks a runner token before the body of its call is read.
 * @property runnerHeartbeat The runner channel.
 * @property orgResolver The member a request acts for.
 */
data class CloudSessionsRoutesDependencies(
    val createCloudSession: CreateCloudSessionUseCase,
    val getCloudSession: GetCloudSessionUseCase,
    val listCloudSessions: ListCloudSessionsUseCase,
    val changeCloudSession: ChangeCloudSessionUseCase,
    val authenticateRunner: AuthenticateRunnerUseCase,
    val runnerHeartbeat: RunnerHeartbeatUseCase,
    val orgResolver: PrincipalOrgResolver,
)
