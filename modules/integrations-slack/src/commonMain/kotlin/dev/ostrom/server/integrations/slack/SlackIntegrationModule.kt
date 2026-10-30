// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack

import dev.ostrom.server.kernel.module.ServerModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The integrations-slack module: the Slack integration: app installation, events, messages.
 *
 * Empty skeleton registered in the app so that its owner only edits this directory (docs/MODULES.md).
 */
object SlackIntegrationModule : ServerModule {
    override val name: String = "integrations-slack"

    override val koinModule: Module = module {}
}
