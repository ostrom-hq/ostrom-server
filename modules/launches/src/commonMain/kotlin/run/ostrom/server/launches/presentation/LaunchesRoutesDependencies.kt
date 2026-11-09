// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.launches.presentation

import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.launches.domain.services.LauncherResolver
import run.ostrom.server.launches.domain.usecases.CheckAgentPolicyUseCase
import run.ostrom.server.launches.domain.usecases.CreateLaunchUseCase
import run.ostrom.server.launches.domain.usecases.EndLaunchUseCase
import run.ostrom.server.launches.domain.usecases.OpenRunnerLaunchUseCase
import run.ostrom.server.launches.domain.usecases.ReportHooksMissingUseCase
import run.ostrom.server.runners.domain.AuthenticateRunnerUseCase
import run.ostrom.server.runners.domain.CheckRunnerTokenUseCase

/**
 * What the launches routes need.
 *
 * @property launchers Resolves the person who launches.
 * @property checkPolicy Reads the organization's policy.
 * @property createLaunch Creates a launch.
 * @property checkRunnerToken Checks a runner token before its call's body is read (S2-46).
 * @property authenticateRunner Reads the cloud session of a runner's current execution.
 * @property openRunnerLaunch Opens a cloud session's launch.
 * @property actors Reads an agent token's launch ([run.ostrom.server.launches.infrastructure.LaunchGateActors]).
 * @property endLaunch Ends a launch.
 * @property reportHooksMissing Records that no hook ran in a launch's session.
 */
data class LaunchesRoutesDependencies(
    val launchers: LauncherResolver,
    val checkPolicy: CheckAgentPolicyUseCase,
    val createLaunch: CreateLaunchUseCase,
    val checkRunnerToken: CheckRunnerTokenUseCase,
    val authenticateRunner: AuthenticateRunnerUseCase,
    val openRunnerLaunch: OpenRunnerLaunchUseCase,
    val actors: GateActorResolver,
    val endLaunch: EndLaunchUseCase,
    val reportHooksMissing: ReportHooksMissingUseCase,
)
