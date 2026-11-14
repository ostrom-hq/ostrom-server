// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.runners.domain.CloudEnvironmentRules
import run.ostrom.server.runners.domain.CloudEnvironmentSpec
import run.ostrom.server.runners.domain.CloudRepository
import run.ostrom.server.runners.domain.CloudSetup
import run.ostrom.server.runners.domain.InvalidCloudSessionRequestException
import run.ostrom.server.runners.domain.PreparationFailure
import run.ostrom.server.runners.domain.PreparationPhase
import run.ostrom.server.runners.domain.PreparationStep
import run.ostrom.server.runners.domain.PreparedEnvironment
import run.ostrom.server.runners.domain.RunnerPreparationReport
import kotlin.uuid.Uuid

/** Maps a requested recipe to the domain: a repository named without a path is cloned under its name. */
fun CloudEnvironmentDto.toDomain() = CloudEnvironmentSpec(
    repositories = repositories.map {
        CloudRepository(it.url, it.path ?: CloudEnvironmentRules.defaultPath(it.url), it.ref, it.branch)
    },
    workingDirectory = workingDirectory,
    setup = CloudSetup(setup.create, setup.start),
    requiredVariables = requiredVariables,
    requireGitHub = requireGitHub,
    gitEmailDomain = gitEmailDomain,
)

/** Maps a recipe to the API. */
fun CloudEnvironmentSpec.toDto() = CloudEnvironmentDto(
    repositories = repositories.map { CloudRepositoryDto(it.url, it.path, it.ref, it.branch) },
    workingDirectory = workingDirectory,
    setup = CloudSetupDto(setup.create, setup.start),
    requiredVariables = requiredVariables,
    requireGitHub = requireGitHub,
    gitEmailDomain = gitEmailDomain,
)

/** Maps what the runner prepares to the API. */
fun PreparedEnvironment.toResponse() = RunnerEnvironmentResponse(
    environment = spec.toDto(),
    git = RunnerGitIdentityDto(git.name, git.email),
    variables = variables.variables,
    unavailable = variables.unavailable,
)

/**
 * Maps a runner's report to the domain.
 *
 * @param sessionId The session, parsed.
 * @throws InvalidCloudSessionRequestException For an unknown phase or step.
 */
fun RunnerPreparationRequest.toReport(sessionId: Uuid): RunnerPreparationReport {
    val phase = PreparationPhase.fromWire(phase)
        ?: throw InvalidCloudSessionRequestException("Unknown preparation phase.")
    val failure = failure?.let { reported ->
        val step = PreparationStep.fromWire(reported.step)
            ?: throw InvalidCloudSessionRequestException("Unknown preparation step.")
        PreparationFailure(step, reported.index, reported.exitCode)
    }
    return RunnerPreparationReport(sessionId, epoch, phase, failure)
}
