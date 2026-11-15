// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import run.ostrom.server.runners.domain.AuthenticateRunnerUseCase
import run.ostrom.server.runners.domain.CloudFileService
import run.ostrom.server.runners.domain.RunnerConversationUseCase

/**
 * What the agent channel of the runner needs.
 *
 * @property authenticate Fences a call on its execution: the runner token's today, a device owner's later.
 * @property runner The channel's use case.
 * @property files The files messages carry (`runners.cloud.files.v1`); null when this server takes none: the download
 *   is not served and the runner is never told it may take files.
 */
data class RunnerConversationRoutesDependencies(
    val authenticate: AuthenticateRunnerUseCase,
    val runner: RunnerConversationUseCase,
    val files: CloudFileService? = null,
)
