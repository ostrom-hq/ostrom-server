// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.runners.presentation

import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.runners.domain.CheckRunnerTokenUseCase
import run.ostrom.server.runners.domain.ChangeCloudSessionUseCase
import run.ostrom.server.runners.domain.CreateCloudSessionUseCase
import run.ostrom.server.runners.domain.GetCloudSessionUseCase
import run.ostrom.server.runners.domain.ListCloudSessionsUseCase
import run.ostrom.server.runners.domain.RunnerGitHubTokenUseCase
import run.ostrom.server.runners.domain.RunnerHeartbeatUseCase
import run.ostrom.server.runners.domain.SetCloudSessionGitHubAccessUseCase

/**
 * What the cloud session routes need.
 *
 * @property createCloudSession Creates a session.
 * @property getCloudSession Reads one.
 * @property listCloudSessions Lists the caller's.
 * @property changeCloudSession Pauses, resumes, deletes.
 * @property checkRunnerToken Checks a runner token before the body of its call is read.
 * @property runnerHeartbeat The runner channel.
 * @property orgResolver The member a request acts for.
 * @property setGitHubAccess Sets whose GitHub access a session uses.
 * @property runnerGitHubToken Hands a runner its owner's GitHub token.
 */
data class CloudSessionsRoutesDependencies(
    val createCloudSession: CreateCloudSessionUseCase,
    val getCloudSession: GetCloudSessionUseCase,
    val listCloudSessions: ListCloudSessionsUseCase,
    val changeCloudSession: ChangeCloudSessionUseCase,
    val checkRunnerToken: CheckRunnerTokenUseCase,
    val runnerHeartbeat: RunnerHeartbeatUseCase,
    val orgResolver: PrincipalOrgResolver,
    val setGitHubAccess: SetCloudSessionGitHubAccessUseCase,
    val runnerGitHubToken: RunnerGitHubTokenUseCase,
)
