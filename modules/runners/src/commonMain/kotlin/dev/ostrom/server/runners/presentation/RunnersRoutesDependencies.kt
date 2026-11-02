// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.runners.presentation

import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.runners.domain.ListRunnersUseCase

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
 * What the runners route needs.
 *
 * @property listRunners Lists the caller's runners.
 * @property runtime Where they run.
 * @property orgResolver The member a request acts for.
 */
data class RunnersRoutesDependencies(
    val listRunners: ListRunnersUseCase,
    val runtime: RunnerRuntime,
    val orgResolver: PrincipalOrgResolver,
)
