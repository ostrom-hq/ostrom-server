// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.governance.infrastructure

import run.ostrom.server.governance.domain.OpenConfirmationUseCase
import run.ostrom.server.kernel.auth.GovernanceConfirmationPending
import run.ostrom.server.kernel.auth.GovernanceConfirmations
import run.ostrom.server.kernel.auth.GovernanceRequest

/**
 * The kernel's [GovernanceConfirmations] on [OpenConfirmationUseCase]: the 202 a token gets names the console page
 * where an owner or an admin confirms the change.
 *
 * @param open The use case.
 * @param consoleUrl The console's URL, without a trailing slash (`OSTROM_CONSOLE_URL`).
 */
class GovernanceConfirmationsImpl(
    private val open: OpenConfirmationUseCase,
    private val consoleUrl: String,
) : GovernanceConfirmations {
    override suspend fun open(request: GovernanceRequest): GovernanceConfirmationPending {
        val confirmation = open.invoke(request)
        return GovernanceConfirmationPending(
            confirmationId = confirmation.id,
            confirmUrl = "$consoleUrl/confirm/${confirmation.id}",
            expiresAt = confirmation.expiresAt.toString(),
        )
    }
}
