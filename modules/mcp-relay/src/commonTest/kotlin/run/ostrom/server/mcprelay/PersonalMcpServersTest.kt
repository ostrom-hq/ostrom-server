// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.server.mcprelay.domain.ArgumentPolicyPayload
import run.ostrom.server.mcprelay.domain.AssignMcpServerUseCaseImpl
import run.ostrom.server.mcprelay.domain.CreateArgumentPolicyUseCaseImpl
import run.ostrom.server.mcprelay.domain.DeleteArgumentPolicyUseCaseImpl
import run.ostrom.server.mcprelay.domain.InvalidMcpServerException
import run.ostrom.server.mcprelay.domain.ListMcpServersUseCaseImpl
import run.ostrom.server.mcprelay.domain.McpHostedNotAllowedException
import run.ostrom.server.mcprelay.domain.McpRelayRepository
import run.ostrom.server.mcprelay.domain.McpServer
import run.ostrom.server.mcprelay.domain.McpServerAdministration
import run.ostrom.server.mcprelay.domain.McpServerLimitException
import run.ostrom.server.mcprelay.domain.McpServerNameTakenException
import run.ostrom.server.mcprelay.domain.McpServerNotFoundException
import run.ostrom.server.mcprelay.domain.McpServerNotPromotedException
import run.ostrom.server.mcprelay.domain.McpServerPayload
import run.ostrom.server.mcprelay.domain.McpServerPersonalException
import run.ostrom.server.mcprelay.domain.McpSettings
import run.ostrom.server.mcprelay.domain.McpSharing
import run.ostrom.server.mcprelay.domain.McpTransport
import run.ostrom.server.mcprelay.domain.PersonalMcpServersUseCaseImpl
import run.ostrom.server.mcprelay.domain.PolicyEffect
import run.ostrom.server.mcprelay.domain.Promotion
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
import kotlin.test.assertNull
import kotlin.uuid.Uuid

/** A member's own MCP servers (TeamCode #269): theirs to change, an administrator's to review, promote or delete. */
class PersonalMcpServersTest {
    private val repository = InMemoryRelayRepository()
    private val clock = TestClock()
    private val administration = McpServerAdministration(repository, FakeReveal(), { true }, clock)
    private val http = McpServerPayload("mine", McpTransport.HTTP, url = "https://mcp.example.com/mine")
    private val stdio = McpServerPayload(
        "db",
        McpTransport.STDIO,
        stdio = StdioSpec(StdioLauncher.NPM, "db-mcp@1.0.0"),
    )

    private fun personal(store: McpRelayRepository = repository) = PersonalMcpServersUseCaseImpl(
        store,
        McpServerAdministration(store, FakeReveal(), { true }, clock),
        clock,
    )

    @Test
    fun aMemberDeclaresChangesAndDeletesTheirOwnServerAssignedToThemAlone() = runTest {
        val mine = personal().create(Relay.memberA, http.copy(sharing = McpSharing.PER_MEMBER))
        assertEquals(Relay.memberA.memberId, mine.ownerMemberId)
        assertEquals(McpSharing.SHARED, mine.sharing, "A remote server has one credential, the member's")
        assertEquals(listOf(Relay.memberA.memberId), repository.assignments.map { it.memberId })
        assertEquals(listOf(mine), personal().list(Relay.memberA))
        assertEquals(emptyList(), personal().list(Relay.otherA))

        val changed = personal().update(Relay.memberA, "mine", http.copy(url = "https://mcp.example.com/v2"))
        assertEquals(2, changed.version)
        assertFailsWith<InvalidMcpServerException> { personal().update(Relay.memberA, "mine", http.copy(name = "x")) }
        assertFailsWith<McpServerNotFoundException> { personal().update(Relay.otherA, "mine", http) }
        assertFailsWith<McpServerNotFoundException> { personal().delete(Relay.otherA, "mine") }
        assertFailsWith<McpServerNotFoundException> { personal().delete(Relay.memberA, "nope") }
        assertEquals(mine.id, personal().delete(Relay.memberA, "mine").id)
        assertEquals(emptyList(), repository.servers)
    }

    @Test
    fun theOrganizationHasOneNameSpaceAndAMemberKeepsTwentyServers() = runTest {
        repository.servers += Relay.server("grafana")
        assertFailsWith<McpServerNameTakenException> { personal().create(Relay.memberA, http.copy(name = "grafana")) }
        personal().create(Relay.otherA, http.copy(name = "theirs"))
        assertFailsWith<McpServerNameTakenException> { personal().create(Relay.memberA, http.copy(name = "theirs")) }
        // A team server is not one of the member's own.
        assertFailsWith<McpServerNotFoundException> {
            personal().update(Relay.memberA, "grafana", http.copy(name = "grafana"))
        }
        repeat(20) { personal().create(Relay.memberA, http.copy(name = "m$it")) }
        val limit = assertFailsWith<McpServerLimitException> { personal().create(Relay.memberA, http) }
        assertEquals("mcp_server_limit", limit.type)
    }

    @Test
    fun aHostedCommandWaitsForTheTeamsSettingAndIsAlwaysTheMembersOwnProcess() = runTest {
        val refused = assertFailsWith<McpHostedNotAllowedException> { personal().create(Relay.memberA, stdio) }
        assertEquals("hosted_not_allowed", refused.type)
        repository.settings[Relay.orgA] = McpSettings("ostrom", memberHostedMcp = true)
        val hosted = personal().create(Relay.memberA, stdio)
        assertEquals(McpSharing.PER_MEMBER, hosted.sharing)
    }

    @Test
    fun aVanishedServerIsNotFound() = runTest {
        val mine = personal().create(Relay.memberA, http)
        val vanishing = object : McpRelayRepository by repository {
            override suspend fun updateServer(server: McpServer): McpServer? = null

            override suspend fun deleteServer(orgId: Uuid, id: Uuid, ownerMemberId: Uuid?): Boolean = false
        }
        assertFailsWith<McpServerNotFoundException> { personal(vanishing).update(Relay.memberA, mine.name, http) }
        assertFailsWith<McpServerNotFoundException> { personal(vanishing).delete(Relay.memberA, mine.name) }
    }

    @Test
    fun anAdministratorReviewsAndPromotesButNeverChangesOrAssignsAMembersServer() = runTest {
        val mine = personal().create(Relay.memberA, http)
        assertFailsWith<AdminRequiredException> { personal().review(Relay.memberA) }
        assertFailsWith<AdminRequiredException> { personal().promote(Relay.memberA, "mine") }
        assertEquals(listOf(mine), personal().review(Relay.adminA))
        assertEquals(mine, ListMcpServersUseCaseImpl(administration).find(Relay.adminA, "mine"))
        assertEquals(emptyList(), ListMcpServersUseCaseImpl(administration)(Relay.adminA), "The team's only")

        assertFailsWith<McpServerPersonalException> {
            UpdateMcpServerUseCaseImpl(administration)(Relay.adminA, mine.id, http)
        }
        assertFailsWith<McpServerPersonalException> {
            AssignMcpServerUseCaseImpl(repository, FakeReveal(), clock)(
                Relay.adminA,
                mine.id,
                Relay.otherA.memberId,
                ToolPolicy.ALL,
                null,
            )
        }
        assertFailsWith<McpServerPersonalException> {
            UnassignMcpServerUseCaseImpl(repository)(Relay.adminA, mine.id, Relay.memberA.memberId)
        }
        assertFailsWith<McpServerNotFoundException> {
            UnassignMcpServerUseCaseImpl(repository)(Relay.adminA, Uuid.random(), null)
        }
        val policy = ArgumentPolicyPayload("*", emptyList(), PolicyEffect.DENY, "No.")
        assertFailsWith<McpServerPersonalException> {
            CreateArgumentPolicyUseCaseImpl(repository, clock)(Relay.adminA, mine.id, policy)
        }
        assertFailsWith<McpServerPersonalException> {
            DeleteArgumentPolicyUseCaseImpl(repository)(Relay.adminA, mine.id, Uuid.random())
        }

        val promotion = personal().promote(Relay.adminA, "mine")
        assertEquals(Relay.memberA.memberId, promotion.formerOwner)
        val promoted = promotion.server
        assertNull(promoted.ownerMemberId)
        assertEquals(listOf(Relay.memberA.memberId), repository.assignments.map { it.memberId }, "The owner keeps it")
        assertEquals(Promotion(promoted, null), personal().promote(Relay.adminA, "mine"), "A team one, as it is")
        assertFailsWith<McpServerNotFoundException> { personal().promote(Relay.adminA, "nope") }
    }

    @Test
    fun aServerChangedMeanwhileOrUsingItsOwnersSecretsIsNotPromoted() = runTest {
        personal().create(Relay.memberA, http)
        val changed = object : McpRelayRepository by repository {
            override suspend fun promoteServer(orgId: Uuid, id: Uuid, version: Int, limit: Int): McpServer? = null
        }
        assertFailsWith<McpServerNotPromotedException> { personal(changed).promote(Relay.adminA, "mine") }
        val secret = Uuid.random()
        repository.servers += Relay.server("keyed", credential = secret).copy(ownerMemberId = Relay.memberA.memberId)
        assertFailsWith<McpServerNotPromotedException> { personal().promote(Relay.adminA, "keyed") }
        repository.servers += Relay.server("hosted", transport = McpTransport.STDIO).copy(
            ownerMemberId = Relay.memberA.memberId,
            stdio = StdioSpec(StdioLauncher.NPM, "db-mcp@1.0.0", secretEnv = mapOf("TOKEN" to secret)),
        )
        assertFailsWith<McpServerNotPromotedException> { personal().promote(Relay.adminA, "hosted") }
        repository.servers += Relay.server("plain", transport = McpTransport.STDIO).copy(
            ownerMemberId = Relay.memberA.memberId,
            stdio = StdioSpec(StdioLauncher.NPM, "db-mcp@1.0.0"),
        )
        assertNull(personal().promote(Relay.adminA, "plain").server.ownerMemberId)
    }

    @Test
    fun aMembersServerIsCheckedAgainstTheTeamsAgentNamesAndTheirOwn() = runTest {
        repository.servers += Relay.server("grafana.eu")
        assertFailsWith<McpServerNameTakenException> {
            personal().create(Relay.memberA, http.copy(name = "grafana_eu"))
        }
        personal().create(Relay.otherA, http.copy(name = "sentry.eu"))
        // Nobody reaches both another member's server and theirs.
        personal().create(Relay.memberA, http.copy(name = "sentry_eu"))
        // A team server is checked against every member's own.
        personal().create(Relay.otherA, http.copy(name = "loki_eu"))
        assertFailsWith<McpServerNameTakenException> {
            administration.create(Relay.adminA, http.copy(name = "loki.eu"))
        }
    }
}
