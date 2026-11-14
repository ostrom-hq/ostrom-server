// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.server.mcprelay.domain.ArgumentCondition
import run.ostrom.server.mcprelay.domain.ArgumentPolicyPayload
import run.ostrom.server.mcprelay.domain.AssignMcpServerUseCaseImpl
import run.ostrom.server.mcprelay.domain.ConditionOperator
import run.ostrom.server.mcprelay.domain.CreateArgumentPolicyUseCaseImpl
import run.ostrom.server.mcprelay.domain.CreateMcpServerUseCaseImpl
import run.ostrom.server.mcprelay.domain.DeleteArgumentPolicyUseCaseImpl
import run.ostrom.server.mcprelay.domain.DeleteMcpServerUseCaseImpl
import run.ostrom.server.mcprelay.domain.GetMcpSettingsUseCaseImpl
import run.ostrom.server.mcprelay.domain.InvalidMcpServerException
import run.ostrom.server.mcprelay.domain.ListArgumentPoliciesUseCaseImpl
import run.ostrom.server.mcprelay.domain.ListMcpAssignmentsUseCaseImpl
import run.ostrom.server.mcprelay.domain.ListMcpCallsUseCase
import run.ostrom.server.mcprelay.domain.ListMcpCallsUseCaseImpl
import run.ostrom.server.mcprelay.domain.ListMcpServersUseCaseImpl
import run.ostrom.server.mcprelay.domain.McpCall
import run.ostrom.server.mcprelay.domain.McpHostDisabledException
import run.ostrom.server.mcprelay.domain.McpOperation
import run.ostrom.server.mcprelay.domain.McpOutcome
import run.ostrom.server.mcprelay.domain.McpRelayRepository
import run.ostrom.server.mcprelay.domain.McpSecretNotFoundException
import run.ostrom.server.mcprelay.domain.McpServer
import run.ostrom.server.mcprelay.domain.McpServerAdministration
import run.ostrom.server.mcprelay.domain.McpServerNameTakenException
import run.ostrom.server.mcprelay.domain.McpServerNotFoundException
import run.ostrom.server.mcprelay.domain.McpServerPayload
import run.ostrom.server.mcprelay.domain.McpSettings
import run.ostrom.server.mcprelay.domain.McpSharing
import run.ostrom.server.mcprelay.domain.McpTransport
import run.ostrom.server.mcprelay.domain.McpUsageUseCaseImpl
import run.ostrom.server.mcprelay.domain.PolicyEffect
import run.ostrom.server.mcprelay.domain.PutMcpSettingsUseCaseImpl
import run.ostrom.server.mcprelay.domain.StdioLauncher
import run.ostrom.server.mcprelay.domain.StdioSpec
import run.ostrom.server.mcprelay.domain.ToolPolicy
import run.ostrom.server.mcprelay.domain.UnassignMcpServerUseCaseImpl
import run.ostrom.server.mcprelay.domain.UpdateMcpServerUseCaseImpl
import run.ostrom.server.secrets.domain.AdminRequiredException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

/** The admin use cases: only admins write, every reference is checked, and a missing target is a 404. */
class McpAdminUseCasesTest {
    private val repository = InMemoryRelayRepository()
    private val reveal = FakeReveal()
    private var hostConfigured = true
    private val clock = TestClock()

    private fun administration(store: McpRelayRepository = repository) =
        McpServerAdministration(store, reveal, { hostConfigured }, clock)

    private val create = CreateMcpServerUseCaseImpl(administration())
    private val http = McpServerPayload("grafana", McpTransport.HTTP, url = "https://mcp.example.com/grafana")

    private fun stored(name: String = "grafana", sharing: McpSharing = McpSharing.SHARED): McpServer =
        Relay.server(name, sharing = sharing).also { repository.servers += it }

    @Test
    fun anAdminCreatesUpdatesListsAndDeletesAServer() = runTest {
        val created = create(Relay.adminA, http)
        assertEquals(1, created.version)
        assertFailsWith<McpServerNameTakenException> { create(Relay.adminA, http) }
        val updated = UpdateMcpServerUseCaseImpl(administration())(
            Relay.adminA,
            created.id,
            http.copy(credentialHeader = "X-Api-Key"),
        )
        assertEquals(2, updated.version)
        assertEquals(listOf(updated), ListMcpServersUseCaseImpl(administration())(Relay.adminA))
        DeleteMcpServerUseCaseImpl(administration())(Relay.adminA, created.id)
        assertFailsWith<McpServerNotFoundException> {
            DeleteMcpServerUseCaseImpl(administration())(Relay.adminA, created.id)
        }
        assertFailsWith<AdminRequiredException> { create(Relay.memberA, http) }
    }

    @Test
    fun theEndpointOfAServerNeverChangesSoNoStoredCredentialFollowsANewHost() = runTest {
        val created = create(Relay.adminA, http.copy(sharing = McpSharing.PER_MEMBER))
        val update = UpdateMcpServerUseCaseImpl(administration())
        // Each member's own credential, assigned for this host, would be sent to the new one (TeamCode f8d23496).
        val moved = assertFailsWith<InvalidMcpServerException> {
            val elsewhere = http.copy(url = "https://evil.example.com/mcp", sharing = McpSharing.PER_MEMBER)
            update(Relay.adminA, created.id, elsewhere)
        }
        assertEquals(
            "The address of an MCP server cannot change: its credentials never follow a new host. Add another server.",
            moved.message,
        )
        assertFailsWith<InvalidMcpServerException> {
            update(Relay.adminA, created.id, http.copy(transport = McpTransport.STDIO, url = null))
        }
        assertEquals(1, repository.servers.single { it.id == created.id }.version, "Nothing was stored")
    }

    @Test
    fun twoNamesTheAgentWouldSeeAsOneAreRefused() = runTest {
        val dotted = create(Relay.adminA, http.copy(name = "grafana.eu"))
        // The agent sees both as `<prefix>-grafana_eu`: the second would shadow the first.
        val clash =
            assertFailsWith<McpServerNameTakenException> { create(Relay.adminA, http.copy(name = "grafana_eu")) }
        assertEquals("The agent would see grafana_eu as the server grafana.eu already is.", clash.message)
        val other = create(Relay.adminA, http.copy(name = "grafana-eu"))
        assertFailsWith<McpServerNameTakenException> {
            UpdateMcpServerUseCaseImpl(administration())(Relay.adminA, other.id, http.copy(name = "grafana_eu"))
        }
        UpdateMcpServerUseCaseImpl(administration())(Relay.adminA, dotted.id, http.copy(name = "grafana.eu"))
    }

    @Test
    fun anUpdateOfAMissingOrVanishedServerIsNotFound() = runTest {
        val update = UpdateMcpServerUseCaseImpl(administration())
        assertFailsWith<McpServerNotFoundException> { update(Relay.adminA, Uuid.random(), http) }
        val server = stored()
        val vanishing = object : McpRelayRepository by repository {
            override suspend fun updateServer(server: McpServer): McpServer? = null
        }
        assertFailsWith<McpServerNotFoundException> {
            UpdateMcpServerUseCaseImpl(administration(vanishing))(Relay.adminA, server.id, http)
        }
    }

    @Test
    fun anOrgHasAtMostSixtyFourServers() = runTest {
        repeat(64) { repository.servers += Relay.server("s$it") }
        assertFailsWith<InvalidMcpServerException> { create(Relay.adminA, http) }
    }

    @Test
    fun aDeclarationIsCheckedAgainstTheHostSharingAndSecrets() = runTest {
        val secret = Uuid.random()
        val stdio = McpServerPayload(
            "mysql",
            McpTransport.STDIO,
            stdio = StdioSpec(StdioLauncher.PYPI, "mcp-mysql==1.2.0", secretEnv = mapOf("MYSQL_DSN" to secret)),
        )
        hostConfigured = false
        assertFailsWith<McpHostDisabledException> { create(Relay.adminA, stdio) }
        hostConfigured = true
        assertFailsWith<McpSecretNotFoundException> { create(Relay.adminA, stdio) }
        reveal.values[secret] = "dsn"
        assertEquals("mysql", create(Relay.adminA, stdio).name)
        val perMember = http.copy(sharing = McpSharing.PER_MEMBER, credentialSecretId = secret)
        assertFailsWith<InvalidMcpServerException> { create(Relay.adminA, perMember) }
        assertEquals("grafana", create(Relay.adminA, http.copy(credentialSecretId = secret)).name)
    }

    @Test
    fun anAssignmentCarriesACredentialOnlyForOneMemberOfAPerMemberServer() = runTest {
        val assign = AssignMcpServerUseCaseImpl(repository, reveal, clock)
        val shared = stored()
        val perMember = stored("discord", McpSharing.PER_MEMBER)
        val own = Uuid.random()
        reveal.values[own] = "own"
        val member = Relay.memberA.memberId
        assertFailsWith<McpServerNotFoundException> {
            assign(Relay.adminA, Uuid.random(), member, ToolPolicy.ALL, null)
        }
        assertFailsWith<InvalidMcpServerException> { assign(Relay.adminA, shared.id, member, ToolPolicy.ALL, own) }
        assertFailsWith<InvalidMcpServerException> { assign(Relay.adminA, perMember.id, null, ToolPolicy.ALL, own) }
        assertFailsWith<McpSecretNotFoundException> {
            assign(Relay.adminA, perMember.id, member, ToolPolicy.ALL, Uuid.random())
        }
        assertEquals(own, assign(Relay.adminA, perMember.id, member, ToolPolicy(setOf("send")), own).credentialSecretId)
        assertEquals(null, assign(Relay.adminA, shared.id, null, ToolPolicy.ALL, null).memberId)

        val list = ListMcpAssignmentsUseCaseImpl(repository)
        assertEquals(1, list(Relay.adminA, perMember.id).size)
        assertFailsWith<McpServerNotFoundException> { list(Relay.adminA, Uuid.random()) }
        val unassign = UnassignMcpServerUseCaseImpl(repository)
        unassign(Relay.adminA, perMember.id, member)
        assertFailsWith<McpServerNotFoundException> { unassign(Relay.adminA, perMember.id, member) }
        assertFailsWith<AdminRequiredException> { unassign(Relay.memberA, shared.id, null) }
    }

    @Test
    fun policiesNeedTheirServerAndAreBounded() = runTest {
        val server = stored()
        val createPolicy = CreateArgumentPolicyUseCaseImpl(repository, clock)
        val payload = ArgumentPolicyPayload(
            "query",
            listOf(ArgumentCondition("db", ConditionOperator.PREFIX, "prod")),
            PolicyEffect.DENY,
            "No production.",
        )
        assertFailsWith<McpServerNotFoundException> { createPolicy(Relay.adminA, Uuid.random(), payload) }
        val policy = createPolicy(Relay.adminA, server.id, payload)
        repeat(63) { createPolicy(Relay.adminA, server.id, payload) }
        assertFailsWith<InvalidMcpServerException> { createPolicy(Relay.adminA, server.id, payload) }

        val listPolicies = ListArgumentPoliciesUseCaseImpl(repository)
        assertEquals(64, listPolicies(Relay.adminA, server.id).size)
        assertFailsWith<McpServerNotFoundException> { listPolicies(Relay.adminA, Uuid.random()) }
        val delete = DeleteArgumentPolicyUseCaseImpl(repository)
        delete(Relay.adminA, server.id, policy.id)
        assertFailsWith<McpServerNotFoundException> { delete(Relay.adminA, server.id, policy.id) }
        assertFailsWith<AdminRequiredException> { delete(Relay.memberA, server.id, policy.id) }
    }

    @Test
    fun settingsAreReadByAnyoneAndWrittenByAdmins() = runTest {
        val put = PutMcpSettingsUseCaseImpl(repository)
        assertEquals("ostrom", GetMcpSettingsUseCaseImpl(repository)(Relay.memberA).agentPrefix)
        assertEquals("teamcode", put(Relay.adminA, McpSettings("teamcode")).agentPrefix)
        assertEquals("teamcode", GetMcpSettingsUseCaseImpl(repository)(Relay.memberA).agentPrefix)
        assertFailsWith<InvalidMcpServerException> { put(Relay.adminA, McpSettings("Bad")) }
        assertFailsWith<AdminRequiredException> { put(Relay.memberA, McpSettings("x")) }
    }

    @Test
    fun aMemberReadsOnlyItsOwnCallsAndUsageAndAnAdminAnyones() = runTest {
        val server = stored()
        for (caller in listOf(Relay.memberA, Relay.otherA)) {
            repository.journal += McpCall(
                Uuid.random(),
                Relay.orgA,
                caller.memberId,
                server.id,
                server.name,
                McpOperation.CALL,
                "query",
                McpOutcome.OK,
                Relay.epoch,
                3,
                10,
            )
        }
        val calls: ListMcpCallsUseCase = ListMcpCallsUseCaseImpl(repository)
        assertEquals(1, calls(Relay.adminA, null, 0).size, "The limit is at least 1")
        assertEquals(2, calls(Relay.adminA, null, 500).size, "Defaults: every server and member")
        val asked = calls(Relay.memberA, null, 50, memberId = Relay.otherA.memberId)
        assertEquals(Relay.memberA.memberId, asked.single().memberId, "A member never reads another's calls")
        assertEquals(Relay.memberA.memberId, calls(Relay.memberA, null, 50).single().memberId)
        assertEquals(1, calls(Relay.adminA, null, 50, "grafana", Relay.otherA.memberId).size)

        val usage = McpUsageUseCaseImpl(repository, clock)
        assertEquals(2, usage(Relay.adminA, 400).size)
        assertEquals(Relay.memberA.memberId, usage(Relay.memberA, 0).single().memberId)
    }
}
