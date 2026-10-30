// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay

import dev.ostrom.server.mcprelay.domain.ArgumentCondition
import dev.ostrom.server.mcprelay.domain.ArgumentPolicy
import dev.ostrom.server.mcprelay.domain.ConditionOperator
import dev.ostrom.server.mcprelay.domain.InvalidMcpServerException
import dev.ostrom.server.mcprelay.domain.McpRules
import dev.ostrom.server.mcprelay.domain.McpServer
import dev.ostrom.server.mcprelay.domain.McpTransport
import dev.ostrom.server.mcprelay.domain.PolicyEffect
import dev.ostrom.server.mcprelay.domain.StdioLauncher
import dev.ostrom.server.mcprelay.domain.StdioSpec
import dev.ostrom.server.mcprelay.domain.ToolPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/** What an admin may declare: every rule refuses on its own, and a valid declaration passes. */
class McpRulesTest {
    private val http = Relay.server()
    private val secret = Uuid.random()
    private val stdio = Relay.server("mysql", transport = McpTransport.STDIO).copy(
        stdio = StdioSpec(
            StdioLauncher.PYPI,
            "mcp-mysql==1.2.0",
            listOf("--dsn", "\${MYSQL_DSN}"),
            mapOf("MYSQL_MODE" to "ro"),
            mapOf("MYSQL_DSN" to secret),
        ),
    )

    private fun refused(server: McpServer) {
        assertFailsWith<InvalidMcpServerException>(server.toString()) { McpRules.requireServer(server) }
    }

    private fun McpServer.withStdio(change: StdioSpec.() -> StdioSpec) = copy(stdio = requireNotNull(stdio).change())

    @Test
    fun validServersPass() {
        McpRules.requireServer(http)
        McpRules.requireServer(http.copy(credentialHeader = "X-Api-Key"))
        McpRules.requireServer(stdio)
        McpRules.requireServer(
            stdio.withStdio {
                copy(launcher = StdioLauncher.NPM, packageName = "@scope/server-mysql@1.2.3-beta.1", args = emptyList())
            },
        )
    }

    @Test
    fun aServerNameIsShortLowerCaseAndNotReserved() {
        refused(http.copy(name = "Grafana"))
        refused(http.copy(name = "settings"))
        refused(http.copy(name = "a".repeat(65)))
    }

    @Test
    fun aCredentialHeaderIsWellFormedAndNotAProtocolHeader() {
        refused(http.copy(credentialHeader = "X Api"))
        refused(http.copy(credentialHeader = "Cookie"))
    }

    @Test
    fun anHttpServerHasAnHttpsUrlWithoutSpacesAndNoCommand() {
        refused(http.copy(url = null))
        refused(http.copy(url = "http://mcp.example.com"))
        refused(http.copy(url = "https://" + "a".repeat(2048)))
        refused(http.copy(url = "https://mcp.example.com/a b"))
        refused(http.copy(url = "https://mcp.example.com/a\u0007"))
        refused(http.copy(stdio = stdio.stdio))
    }

    @Test
    fun aStdioServerHasACommandAndNeitherUrlNorSharedCredentialNorHeader() {
        refused(stdio.copy(stdio = null))
        refused(stdio.copy(url = "https://mcp.example.com"))
        refused(stdio.copy(credentialSecretId = secret))
        refused(stdio.copy(credentialHeader = "X-Api-Key"))
    }

    @Test
    fun aPackageIsPinnedAndItsArgumentsAreBounded() {
        refused(stdio.withStdio { copy(packageName = "mcp-mysql") })
        refused(stdio.withStdio { copy(launcher = StdioLauncher.NPM, packageName = "server-mysql@latest") })
        refused(stdio.withStdio { copy(args = List(33) { "a" }) })
        refused(stdio.withStdio { copy(args = listOf("a".repeat(513))) })
        refused(stdio.withStdio { copy(args = listOf("a\nb")) })
        refused(stdio.withStdio { copy(args = listOf("\${OTHER}")) })
    }

    @Test
    fun variablesAreFewWellNamedNotReservedAndEitherVisibleOrSecret() {
        refused(stdio.withStdio { copy(env = (1..48).associate { "V$it" to "x" }) })
        refused(stdio.withStdio { copy(env = mapOf("MYSQL_DSN" to "x")) })
        refused(stdio.withStdio { copy(env = mapOf("lower" to "x")) })
        refused(stdio.withStdio { copy(env = mapOf("PATH" to "x")) })
        refused(stdio.withStdio { copy(env = mapOf("LD_PRELOAD" to "x")) })
        refused(stdio.withStdio { copy(env = mapOf("MODE" to "a".repeat(1025))) })
        refused(stdio.withStdio { copy(env = mapOf("MODE" to "a\u0000")) })
        refused(stdio.withStdio { copy(args = emptyList(), secretEnv = (1..17).associate { "S$it" to secret }) })
    }

    @Test
    fun anAllowlistIsBoundedAndNamesValidTools() {
        McpRules.requirePolicy(ToolPolicy(null))
        McpRules.requirePolicy(ToolPolicy(setOf("query_loki_logs")))
        assertFailsWith<InvalidMcpServerException> { McpRules.requirePolicy(ToolPolicy(setOf("bad tool"))) }
        assertFailsWith<InvalidMcpServerException> {
            McpRules.requirePolicy(ToolPolicy((0..256).map { "t$it" }.toSet()))
        }
    }

    private fun policy(tool: String = "query", message: String = "No.", vararg conditions: ArgumentCondition) =
        ArgumentPolicy(
            Uuid.random(),
            Relay.orgA,
            Uuid.random(),
            tool,
            conditions.toList(),
            PolicyEffect.DENY,
            message,
            Relay.epoch,
        )

    @Test
    fun anArgumentPolicyNamesAToolExplainsItselfAndBoundsItsConditions() {
        McpRules.requireArgumentPolicy(policy(ArgumentPolicy.ANY_TOOL))
        McpRules.requireArgumentPolicy(
            policy(
                "query",
                "No.",
                ArgumentCondition("db", ConditionOperator.EXISTS),
                ArgumentCondition("db", ConditionOperator.MATCHES, "^prod"),
            ),
        )
        val refusals = listOf(
            policy("bad tool"),
            policy(message = " "),
            policy(message = "a".repeat(501)),
            policy("q", "No.", *Array(17) { ArgumentCondition("db", ConditionOperator.EXISTS) }),
            policy("q", "No.", ArgumentCondition(" ", ConditionOperator.EXISTS)),
            policy("q", "No.", ArgumentCondition("a".repeat(257), ConditionOperator.EXISTS)),
            policy("q", "No.", ArgumentCondition("db", ConditionOperator.EQUALS)),
            policy("q", "No.", ArgumentCondition("db", ConditionOperator.EQUALS, "a".repeat(1025))),
            policy("q", "No.", ArgumentCondition("db", ConditionOperator.MATCHES, "(a+)+")),
        )
        for (refused in refusals) {
            assertFailsWith<InvalidMcpServerException>(refused.toString()) { McpRules.requireArgumentPolicy(refused) }
        }
    }

    @Test
    fun thePrefixIsShortAndLowerCaseAndNamesTheAgentsServer() {
        McpRules.requirePrefix("teamcode")
        assertFailsWith<InvalidMcpServerException> { McpRules.requirePrefix("Team") }
        assertEquals("ostrom-grafana_ro", McpRules.agentName("ostrom", "grafana.ro"))
    }
}
