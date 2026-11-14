// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.mcprelay.domain.HostedProcessChange
import run.ostrom.server.mcprelay.domain.HostedProcessRecord
import run.ostrom.server.mcprelay.domain.HostedProcesses
import run.ostrom.server.mcprelay.domain.ListHostedProcessesUseCase
import run.ostrom.server.mcprelay.domain.McpAssignment
import run.ostrom.server.mcprelay.domain.McpRelayRepository
import run.ostrom.server.mcprelay.domain.McpSharing
import run.ostrom.server.mcprelay.domain.McpTransport
import run.ostrom.server.mcprelay.domain.RestartHostedProcessUseCase
import run.ostrom.server.mcprelay.domain.StdioLauncher
import run.ostrom.server.mcprelay.domain.StdioSpec
import run.ostrom.server.mcprelay.domain.ToolPolicy
import run.ostrom.server.mcprelay.presentation.McpHostedRestartDto
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.testing.serverTest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** The recorded states of hosted processes on PostgreSQL, and the hosted routes over HTTP. */
class McpHostedPostgresTest : RelayPostgresHarness() {
    private val spec = StdioSpec(StdioLauncher.NPM, "mcp-x@1.0.0", emptyList(), emptyMap(), emptyMap())

    @Test
    fun theStatesAreWrittenByCompareAndSetAndConfinedToTheirOrg() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            val repository = server.koin.get<McpRelayRepository>()
            val shared = Relay.server("shared", transport = McpTransport.STDIO).copy(stdio = spec)
            val personal = Relay.server("personal", transport = McpTransport.STDIO, sharing = McpSharing.PER_MEMBER)
                .copy(stdio = spec)
            val other = Relay.server("other", org = Relay.orgB, transport = McpTransport.STDIO).copy(stdio = spec)
            for (it in listOf(shared, personal, other, Relay.server("http"))) repository.createServer(it)
            for (it in listOf(shared, personal, other)) {
                repository.putAssignment(McpAssignment(it.orgId, it.id, null, ToolPolicy.ALL, null, Relay.epoch))
            }
            val hosted = repository.orgHostedAssignments(Relay.orgA, 10)!!
            assertEquals(setOf("shared", "personal"), hosted.map { it.server.name }.toSet(), "Stdio servers of org A")
            assertNull(repository.orgHostedAssignments(Relay.orgA, 1), "More rows than the limit: none")

            val sharedReady = HostedProcessRecord(Relay.orgA, shared.id, null, "k1", "ready", null, Relay.epoch)
            val mine = HostedProcessRecord(
                Relay.orgA, personal.id, Relay.memberA.memberId, "k2", "failed", "crashed", Relay.epoch,
            )
            val inserts = listOf(HostedProcessChange(null, sharedReady), HostedProcessChange(null, mine))
            assertEquals(2, repository.applyStdioStates(Relay.orgA, inserts, emptyList()))
            assertEquals(0, repository.applyStdioStates(Relay.orgA, inserts, emptyList()), "Already written")
            val later = Relay.epoch + 1.minutes
            val recovered = mine.copy(state = "ready", failure = null, changedAt = later)
            val update = HostedProcessChange(mine, recovered)
            assertEquals(1, repository.applyStdioStates(Relay.orgA, listOf(update), emptyList()))
            assertEquals(0, repository.applyStdioStates(Relay.orgA, listOf(update), emptyList()), "A stale read")
            assertEquals(setOf(sharedReady, recovered), repository.stdioStates(Relay.orgA, 10)!!.toSet())
            assertNull(repository.stdioStates(Relay.orgA, 1))
            assertEquals(
                setOf(sharedReady, recovered),
                repository.memberStdioStates(Relay.orgA, listOf(shared.id, personal.id), Relay.memberA.memberId)
                    .toSet(),
            )
            assertEquals(
                listOf(sharedReady),
                repository.memberStdioStates(Relay.orgA, listOf(shared.id, personal.id), Relay.otherA.memberId),
            )
            assertEquals(emptyList(), repository.memberStdioStates(Relay.orgA, emptyList(), Relay.memberA.memberId))
            // Another org reads none of them, and a stale removal deletes nothing.
            assertEquals(emptyList(), repository.stdioStates(Relay.orgB, 10))
            repository.applyStdioStates(Relay.orgA, emptyList(), listOf(mine))
            repository.applyStdioStates(Relay.orgA, emptyList(), listOf(sharedReady, recovered))
            assertEquals(emptyList(), repository.stdioStates(Relay.orgA, 10))
        }

    @Test
    fun theListAndARestartAreAnsweredOnceAnMcpHostServesThem() {
        val restarted = mutableListOf<Pair<String, Uuid?>>()
        val journal = mutableListOf<OrgAuditRecord>()
        val withHost = module {
            includes(overrides)
            single<OrgAuditTrail> { OrgAuditTrail { journal += it } }
            single<ListHostedProcessesUseCase> {
                object : ListHostedProcessesUseCase {
                    override suspend fun invoke(caller: Caller) = HostedProcesses(true, "1.0.0", emptyList())
                }
            }
            single<RestartHostedProcessUseCase> {
                object : RestartHostedProcessUseCase {
                    override suspend fun invoke(
                        caller: Caller,
                        server: String,
                        memberId: Uuid?,
                        journal: suspend () -> Unit,
                    ) {
                        journal()
                        restarted += server to memberId
                    }
                }
            }
        }
        serverTest(listOf(SecretsModule, McpRelayModule), withHost) { server ->
            with(server) {
                val list = client.get("$admin/hosted") { bearerAuth("admin-a") }
                assertEquals(HttpStatusCode.OK, list.status, list.bodyAsText())
                assertTrue("\"hostReachable\":true" in list.bodyAsText(), list.bodyAsText())
                val restart = "$admin/hosted/restart"
                val shared = sendJson(HttpMethod.Post, restart, McpHostedRestartDto("shared"))
                assertEquals(HttpStatusCode.NoContent, shared.status, shared.bodyAsText())
                val member = Relay.memberA.memberId
                val personal = McpHostedRestartDto("personal", member.toString())
                assertEquals(HttpStatusCode.NoContent, sendJson(HttpMethod.Post, restart, personal).status)
                assertEquals(listOf("shared" to null, "personal" to member), restarted)
                assertEquals(listOf("shared", "personal/$member"), journal.map { it.subjectId })
                assertTrue(journal.all { it.event == "mcp.stdio.restarted" && it.orgId == Relay.orgA })
            }
        }
    }

    @Test
    fun theHostedRoutesNeedACredentialAnAdministratorAndAnMcpHost() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            with(server) {
                val hosted = "$admin/hosted"
                assertEquals(HttpStatusCode.Unauthorized, client.get(hosted).status)
                val restart = McpHostedRestartDto("shared")
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    sendJson(HttpMethod.Post, "$hosted/restart", restart, "no-such-token").status,
                )
                val noHost = client.get(hosted) { bearerAuth("admin-a") }
                assertEquals(HttpStatusCode.Conflict, noHost.status, noHost.bodyAsText())
                assertEquals(HttpStatusCode.Conflict, sendJson(HttpMethod.Post, "$hosted/restart", restart).status)
                val stale = sendJson(HttpMethod.Post, "$hosted/restart", restart, "stale-admin-a")
                assertTrue("step_up_required" in stale.bodyAsText(), stale.bodyAsText())
                val badMember = sendJson(HttpMethod.Post, "$hosted/restart", McpHostedRestartDto("shared", "nope"))
                assertEquals(HttpStatusCode.NotFound, badMember.status)
                val member = client.get(hosted) { bearerAuth("member-a") }
                assertEquals(HttpStatusCode.Forbidden, member.status, member.bodyAsText())
            }
        }
}
