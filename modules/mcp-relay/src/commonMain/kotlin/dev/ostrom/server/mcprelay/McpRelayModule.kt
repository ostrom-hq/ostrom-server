package dev.ostrom.server.mcprelay

import dev.ostrom.server.kernel.module.ServerModule
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * The mcp-relay module: the MCP relay: servers, tools and the access each member has.
 *
 * Empty skeleton registered in the app so that its owner only edits this directory (docs/MODULES.md).
 */
object McpRelayModule : ServerModule {
    override val name: String = "mcp-relay"

    override val koinModule: Module = module {}
}
