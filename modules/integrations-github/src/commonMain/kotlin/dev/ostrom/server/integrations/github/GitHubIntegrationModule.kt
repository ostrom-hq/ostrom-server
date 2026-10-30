package dev.ostrom.server.integrations.github

import dev.ostrom.server.kernel.module.ServerModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The integrations-github module: the GitHub integration: app installation, webhooks, pull requests.
 *
 * Empty skeleton registered in the app so that its owner only edits this directory (docs/MODULES.md).
 */
object GitHubIntegrationModule : ServerModule {
    override val name: String = "integrations-github"

    override val koinModule: Module = module {}
}
