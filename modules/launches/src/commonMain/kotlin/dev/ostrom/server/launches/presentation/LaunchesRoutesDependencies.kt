// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.launches.presentation

import dev.ostrom.server.launches.domain.services.LauncherResolver
import dev.ostrom.server.launches.domain.usecases.CheckAgentPolicyUseCase
import dev.ostrom.server.launches.domain.usecases.CreateLaunchUseCase

/**
 * What the launches routes need.
 *
 * @property launchers Resolves the person who launches.
 * @property checkPolicy Reads the organization's policy.
 * @property createLaunch Creates a launch.
 */
data class LaunchesRoutesDependencies(
    val launchers: LauncherResolver,
    val checkPolicy: CheckAgentPolicyUseCase,
    val createLaunch: CreateLaunchUseCase,
)
