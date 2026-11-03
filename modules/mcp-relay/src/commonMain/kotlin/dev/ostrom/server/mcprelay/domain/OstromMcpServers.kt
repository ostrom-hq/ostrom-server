// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay.domain

/**
 * Ostrom's own MCP servers, as the agent knows them: `ostrom`, which the local relay of `ostrom claude` serves itself
 * (`/mcp/ostrom`, the waits), and `ostrom-workflow`, which every session package declares (`PackageCompiler`). The
 * launcher lists an organization's servers by name next to them, so an organization's server with one of these names
 * would never be reached: [McpRules.requireServer] refuses it, whatever its case and surrounding spaces.
 */
object OstromMcpServers {
    /** Their names. */
    val NAMES: Set<String> = setOf("ostrom", "ostrom-workflow")

    /**
     * Whether [name] is one of [NAMES], whatever its case and surrounding spaces.
     *
     * @param name A server name.
     * @return Whether an organization's server may not take it.
     */
    fun isOwn(name: String): Boolean = name.trim().lowercase() in NAMES
}
