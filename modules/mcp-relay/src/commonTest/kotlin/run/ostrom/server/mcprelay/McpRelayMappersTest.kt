// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.server.mcprelay.domain.HostedProcess
import run.ostrom.server.mcprelay.domain.HostedProcessStatus
import run.ostrom.server.mcprelay.domain.HostedProcesses
import run.ostrom.server.mcprelay.domain.InvalidMcpServerException
import run.ostrom.server.mcprelay.domain.McpCall
import run.ostrom.server.mcprelay.domain.McpOperation
import run.ostrom.server.mcprelay.domain.McpOutcome
import run.ostrom.server.mcprelay.domain.McpSecretNotFoundException
import run.ostrom.server.mcprelay.domain.McpSharing
import run.ostrom.server.mcprelay.domain.McpTransport
import run.ostrom.server.mcprelay.domain.MemberServer
import run.ostrom.server.mcprelay.domain.StdioLauncher
import run.ostrom.server.mcprelay.domain.StdioSpec
import run.ostrom.server.mcprelay.presentation.ArgumentConditionDto
import run.ostrom.server.mcprelay.presentation.ArgumentPolicyRequest
import run.ostrom.server.mcprelay.presentation.McpHostedProcessDto
import run.ostrom.server.mcprelay.presentation.McpServerDto
import run.ostrom.server.mcprelay.presentation.StdioSpecDto
import run.ostrom.server.mcprelay.presentation.secretIds
import run.ostrom.server.mcprelay.presentation.secretNamesUsed
import run.ostrom.server.mcprelay.presentation.toDto
import run.ostrom.server.mcprelay.presentation.toPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** Servers and calls between the API and the domain: names become ids and back, nothing secret leaks. */
class McpRelayMappersTest {
    private val dsn = Uuid.random()
    private val stdioDto = McpServerDto(
        "mysql",
        transport = "STDIO",
        secretNames = listOf("IGNORED"),
        scope = "per-member",
        stdio = StdioSpecDto("pypi", "mcp-mysql==1.2.0", listOf("--ro"), mapOf("MODE" to "ro"), mapOf("DSN" to "DB")),
    )

    @Test
    fun aStdioDeclarationNamesItsSecretVariablesAndHasNoUrl() {
        assertEquals(listOf("DB"), stdioDto.secretNamesUsed(), "A stdio server has no credential, only variables")
        val payload = stdioDto.copy(url = "https://ignored.example.com").toPayload(mapOf("DB" to dsn))
        assertNull(payload.url)
        assertNull(payload.credentialSecretId)
        assertEquals(McpSharing.PER_MEMBER, payload.sharing)
        assertEquals(
            StdioSpec(
                StdioLauncher.PYPI,
                "mcp-mysql==1.2.0",
                listOf("--ro"),
                mapOf("MODE" to "ro"),
                mapOf(
                    "DSN" to dsn,
                ),
            ),
            payload.stdio,
        )
        assertFailsWith<McpSecretNotFoundException> { stdioDto.toPayload(emptyMap()) }
    }

    @Test
    fun anHttpDeclarationTakesItsFirstSecretAsCredential() {
        val http = McpServerDto("grafana", "", "http", listOf("TOKEN"))
        assertEquals(listOf("TOKEN"), http.secretNamesUsed())
        assertNull(http.toPayload(mapOf("TOKEN" to dsn)).url, "An empty URL is no URL")
        assertFailsWith<McpSecretNotFoundException> { http.toPayload(emptyMap()) }
        assertFailsWith<InvalidMcpServerException> { http.copy(scope = "team").toPayload(mapOf("TOKEN" to dsn)) }
    }

    @Test
    fun aStoredServerMapsBackWithTheNamesStillKnown() {
        val known = Uuid.random()
        val server = Relay.server("mysql", transport = McpTransport.STDIO, sharing = McpSharing.PER_MEMBER).copy(
            stdio = StdioSpec(StdioLauncher.NPM, "mcp@1.0.0", secretEnv = mapOf("A" to known, "B" to Uuid.random())),
        )
        assertEquals(2, server.secretIds().size)
        val dto = server.toDto(mapOf(known to "KNOWN"))
        assertEquals("stdio:npm:mcp@1.0.0", dto.url)
        assertEquals("per-member", dto.scope)
        assertEquals(listOf("KNOWN"), dto.secretNames)
        assertEquals(mapOf("A" to "KNOWN"), dto.stdio!!.secretEnv, "A deleted secret is left out")
        assertEquals("", server.copy(stdio = null).toDto(emptyMap()).url)
    }

    @Test
    fun aMatchesConditionNeedsAPatternAndACallKeepsItsPolicy() {
        val request = ArgumentPolicyRequest("q", listOf(ArgumentConditionDto("db", "matches")), "deny", "No.")
        assertFailsWith<InvalidMcpServerException> { request.toPayload() }
        val policy = Uuid.random()
        val call = McpCall(
            Uuid.random(),
            Relay.orgA,
            Relay.memberA.memberId,
            Uuid.random(),
            "grafana",
            McpOperation.CALL,
            "query",
            McpOutcome.DENIED,
            Relay.epoch,
            1,
            0,
            policy,
        )
        assertEquals(policy.toString(), call.toDto().policyId)
        assertNull(call.copy(policyId = null).toDto().policyId)
        assertNull(call.toDto().launchId)
        assertEquals("lch_1", call.copy(launchId = "lch_1").toDto().launchId)
    }

    @Test
    fun aMembersServerCarriesItsStateAndTheHostedProcessesTheirFigures() {
        val member = MemberServer("fs", "ostrom-fs", McpTransport.STDIO, null, "failed", "crashed").toDto()
        assertEquals("failed" to "crashed", member.state to member.failure)
        val memberId = Uuid.random()
        val live = HostedProcessStatus("ready", "npm", "a@1", 2, 1, 0, null, 900, 1024, "boom")
        val dto = HostedProcesses(
            hostReachable = true,
            hostVersion = "1.2.3",
            processes = listOf(
                HostedProcess("a", memberId, "npm", "a@1", "ready", null, live),
                HostedProcess("b", null, "pypi", "b==1", "idle", "crashed", null),
            ),
        ).toDto()
        assertEquals(
            McpHostedProcessDto("a", memberId.toString(), "npm", "a@1", "ready", null, 2, 1, 0, 900, 1024, "boom"),
            dto.items.first(),
        )
        assertEquals(McpHostedProcessDto("b", null, "pypi", "b==1", "idle", "crashed", 0, 0), dto.items.last())
        assertEquals(true to "1.2.3", dto.hostReachable to dto.hostVersion)
    }
}
