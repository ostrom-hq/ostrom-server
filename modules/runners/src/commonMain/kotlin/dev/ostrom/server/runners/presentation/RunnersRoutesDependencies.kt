// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.presentation

import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.runners.domain.DeleteRunnerUseCase
import dev.ostrom.server.runners.domain.ListRegisteredRunnersUseCase
import dev.ostrom.server.runners.domain.ListRunnersUseCase
import dev.ostrom.server.runners.domain.RegisterRunnerUseCase

/**
 * Where the server runs its runners, for their labels and version.
 *
 * @property driver The runtime (`kubernetes`, `docker`), or null when cloud sessions are off.
 * @property image The runner image, `registry/name:tag`.
 */
data class RunnerRuntime(val driver: String?, val image: String) {
    /** The image's tag, or null when it has none. */
    val version: String?
        get() = image.substringAfterLast('/').substringAfter(':', "").ifEmpty { null }
}

/**
 * What the runners routes need.
 *
 * @property listRunners Lists the caller's runners at work.
 * @property runtime Where they run.
 * @property orgResolver The member a request acts for.
 * @property listRegisteredRunners Lists the organization's registered runners.
 * @property registerRunner Registers one.
 * @property deleteRunner Removes one.
 */
data class RunnersRoutesDependencies(
    val listRunners: ListRunnersUseCase,
    val runtime: RunnerRuntime,
    val orgResolver: PrincipalOrgResolver,
    val listRegisteredRunners: ListRegisteredRunnersUseCase,
    val registerRunner: RegisterRunnerUseCase,
    val deleteRunner: DeleteRunnerUseCase,
)
