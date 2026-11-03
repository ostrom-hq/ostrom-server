// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.governance.infrastructure

import dev.ostrom.server.governance.domain.OpenConfirmationUseCase
import dev.ostrom.server.kernel.auth.GovernanceConfirmationPending
import dev.ostrom.server.kernel.auth.GovernanceConfirmations
import dev.ostrom.server.kernel.auth.GovernanceRequest

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
