// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.runners.domain.CheckRunnerTokenUseCase
import run.ostrom.server.runners.domain.ChangeCloudSessionUseCase
import run.ostrom.server.runners.domain.CreateCloudSessionUseCase
import run.ostrom.server.runners.domain.CreateRemoteSessionUseCase
import run.ostrom.server.runners.domain.HostRemoteSessionUseCase
import run.ostrom.server.runners.domain.GetCloudSessionUseCase
import run.ostrom.server.runners.domain.ListCloudSessionsUseCase
import run.ostrom.server.runners.domain.RunnerEnvironmentUseCase
import run.ostrom.server.runners.domain.RunnerInferenceUseCase
import run.ostrom.server.runners.domain.RunnerGitHubTokenUseCase
import run.ostrom.server.runners.domain.RunnerHeartbeatUseCase
import run.ostrom.server.runners.domain.RunnerVariablesUseCase
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
 * @property runnerVariables Hands a runner its owner's environment variables.
 * @property runnerEnvironment Hands a runner what to prepare, and records its preparation; null without the orgs
 *   module, whose routes are then not served.
 * @property createRemoteSession Creates a remote session on the caller's device.
 * @property hostRemoteSession Starts a remote session's next execution on its host.
 * @property hosts Shows each remote session's device.
 * @property runnerInference Hands a runner its execution's inference credential; null without the launches module,
 *   whose route is then not served (404: the agent runs with its own provider configuration).
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
    val runnerVariables: RunnerVariablesUseCase,
    val runnerEnvironment: RunnerEnvironmentUseCase? = null,
    val createRemoteSession: CreateRemoteSessionUseCase,
    val hostRemoteSession: HostRemoteSessionUseCase,
    val hosts: RemoteHostViews,
    val runnerInference: RunnerInferenceUseCase? = null,
)
