// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.slack

import run.ostrom.server.kernel.module.ServerModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The integrations-slack module: the Slack integration: app installation, events, messages.
 *
 * Empty skeleton registered in the app so that its owner only edits this directory (docs/MODULES.md). It lists no
 * feature in `/api/v1/capabilities` (`slack.v1` comes with its first served route and its configuration).
 */
object SlackIntegrationModule : ServerModule {
    override val name: String = "integrations-slack"

    override val koinModule: Module = module {}
}
