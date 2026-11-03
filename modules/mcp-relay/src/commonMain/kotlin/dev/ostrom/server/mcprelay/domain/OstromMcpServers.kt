// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay.domain

/**
 * Ostrom's own MCP servers, as the agent knows them: `ostrom`, which the local relay of `ostrom claude` serves itself
 * (`/mcp/ostrom`, the waits), and `ostrom-workflow`, which every session package declares (`PackageCompiler`, in the
 * compiler module: a new own server goes in both). The launcher gives an organization's server the label
 * `ostrom-<name>` (a name that starts with `ostrom` keeps it, `.` becomes `_`: `mcpLabel` of ostrom-cli) next to them,
 * so a server whose label is one of theirs would never be reached: [McpRules.requireServer] refuses it.
 */
object OstromMcpServers {
    /** Their names. */
    val NAMES: Set<String> = setOf("ostrom", "ostrom-workflow")

    private const val LABEL_PREFIX = "ostrom"

    /**
     * The label an agent gives an organization's server, whatever its case and surrounding spaces.
     *
     * @param name A server name.
     * @return `ostrom-<name>`, or the name itself when it starts with `ostrom`, with `.` as `_`.
     */
    fun labelOf(name: String): String {
        val plain = name.trim().lowercase()
        val label = if (plain.startsWith(LABEL_PREFIX)) plain else "$LABEL_PREFIX-$plain"
        return label.replace('.', '_')
    }

    /**
     * Whether an agent would see [name] as one of Ostrom's own servers: `ostrom`, `ostrom-workflow`, `workflow`.
     *
     * @param name A server name.
     * @return Whether an organization's server may not take it.
     */
    fun isOwn(name: String): Boolean = labelOf(name) in NAMES
}
