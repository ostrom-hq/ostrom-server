// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.presentation

import dev.ostrom.server.launches.domain.services.LauncherResolver
import dev.ostrom.server.launches.domain.usecases.CheckAgentPolicyUseCase
import dev.ostrom.server.launches.domain.usecases.CreateLaunchUseCase
import dev.ostrom.server.launches.domain.usecases.OpenRunnerLaunchUseCase
import dev.ostrom.server.runners.domain.AuthenticateRunnerUseCase
import dev.ostrom.server.runners.domain.CheckRunnerTokenUseCase

/**
 * What the launches routes need.
 *
 * @property launchers Resolves the person who launches.
 * @property checkPolicy Reads the organization's policy.
 * @property createLaunch Creates a launch.
 * @property checkRunnerToken Checks a runner token before its call's body is read (S2-46).
 * @property authenticateRunner Reads the cloud session of a runner's current execution.
 * @property openRunnerLaunch Opens a cloud session's launch.
 */
data class LaunchesRoutesDependencies(
    val launchers: LauncherResolver,
    val checkPolicy: CheckAgentPolicyUseCase,
    val createLaunch: CreateLaunchUseCase,
    val checkRunnerToken: CheckRunnerTokenUseCase,
    val authenticateRunner: AuthenticateRunnerUseCase,
    val openRunnerLaunch: OpenRunnerLaunchUseCase,
)
