// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.BootstrapInstanceUseCase
import run.ostrom.server.kernel.module.InstanceBootstrapper
import run.ostrom.server.kernel.module.SetupLinkOutput

/**
 * [InstanceBootstrapper] on [BootstrapInstanceUseCase]: the link opens the console's setup page.
 *
 * @param bootstrap Makes the link.
 * @param setupUrl The console URL of a token.
 */
class IdentityInstanceBootstrapper(
    private val bootstrap: BootstrapInstanceUseCase,
    private val setupUrl: (String) -> String,
) : InstanceBootstrapper {
    override suspend fun setupLink(): SetupLinkOutput {
        val issued = bootstrap()
        return SetupLinkOutput(setupUrl(issued.token), issued.link.expiresAt.toString())
    }
}
