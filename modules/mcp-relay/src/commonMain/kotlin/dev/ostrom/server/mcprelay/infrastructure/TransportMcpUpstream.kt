// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay.infrastructure

import dev.ostrom.server.mcprelay.domain.McpOutcome
import dev.ostrom.server.mcprelay.domain.McpTransport
import dev.ostrom.server.mcprelay.domain.McpUpstream
import dev.ostrom.server.mcprelay.domain.UpstreamResult
import dev.ostrom.server.mcprelay.domain.UpstreamTarget
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration

/**
 * Sends each operation to the upstream of its server's transport.
 *
 * @param http HTTPS servers.
 * @param host Stdio servers, or null when no MCP host is configured.
 */
class TransportMcpUpstream(private val http: McpUpstream, private val host: McpUpstream?) : McpUpstream {
    override suspend fun listTools(target: UpstreamTarget, deadline: Duration): UpstreamResult<List<JsonObject>> =
        upstream(target)?.listTools(target, deadline) ?: NO_HOST

    override suspend fun callTool(
        target: UpstreamTarget,
        tool: String,
        arguments: JsonObject,
        deadline: Duration,
    ): UpstreamResult<JsonObject> = upstream(target)?.callTool(target, tool, arguments, deadline) ?: NO_HOST

    private fun upstream(target: UpstreamTarget): McpUpstream? = when (target.server.transport) {
        McpTransport.HTTP -> http
        McpTransport.STDIO -> host
    }

    private companion object {
        val NO_HOST = UpstreamResult.Failure(McpOutcome.UPSTREAM_ERROR, "No MCP host is configured on this server.")
    }
}
