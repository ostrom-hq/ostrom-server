// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.mcprelay

import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.events.OrgEventDraft
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.mcprelay.domain.HostedProcessChange
import run.ostrom.server.mcprelay.domain.HostedProcessEvent
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
import run.ostrom.server.mcprelay.infrastructure.McpRelayRepositoryImpl
import run.ostrom.server.mcprelay.presentation.McpHostedRestartDto
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.Caller
import run.ostrom.server.testing.serverTest
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    // What each publication was given, and the organization its transaction was confined to when it came.
    private class Recorder : OrgEventPublisher {
        val published = mutableListOf<Pair<String, List<OrgEventDraft>>>()

        override suspend fun publish(orgId: Uuid, events: List<OrgEventDraft>) = error("In the state's transaction")

        override suspend fun publishIn(transaction: Transaction, orgId: Uuid, events: List<OrgEventDraft>) {
            val org = transaction.fetchAll(Statement.create("SELECT current_setting('ostrom.org_id') AS org"))
                .getOrThrow().first().get("org").asString()
            published += org to events
        }
    }

    @Test
    fun aStreakOfFailuresIsOneEventEachWayInTheStatesTransactionAndAStaleWriteHasNone() =
        serverTest(listOf(SecretsModule, McpRelayModule), overrides) { server ->
            val setup = server.koin.get<McpRelayRepository>()
            val shared = Relay.server("shared", transport = McpTransport.STDIO).copy(stdio = spec)
            val personal = Relay.server("personal", transport = McpTransport.STDIO, sharing = McpSharing.PER_MEMBER)
                .copy(stdio = spec)
            for (it in listOf(shared, personal)) setup.createServer(it)
            val recorder = Recorder()
            val repository = McpRelayRepositoryImpl(server.driver, recorder)
            val member = Relay.memberA.memberId
            val sharedFailed = HostedProcessRecord(Relay.orgA, shared.id, null, "k1", "failed", "crashed", Relay.epoch)
            val mineBackoff = HostedProcessRecord(
                Relay.orgA, personal.id, member, "k2", "backoff", "start_failed", Relay.epoch,
            )
            val inserts = listOf(
                HostedProcessChange(null, sharedFailed, HostedProcessEvent.FAILED),
                HostedProcessChange(null, mineBackoff, HostedProcessEvent.FAILED),
            )
            assertEquals(2, repository.applyStdioStates(Relay.orgA, inserts, emptyList()))
            val (org, failed) = recorder.published.single()
            assertEquals(Relay.orgA.toString(), org, "Confined to the organization of the states")
            assertEquals(
                listOf(
                    OrgEventDraft(
                        OrgEventType.MCP_STDIO_FAILED, "shared", null,
                        JsonObject(mapOf("server" to JsonPrimitive("shared"), "state" to JsonPrimitive("failed"),
                            "failure" to JsonPrimitive("crashed"))),
                    ),
                    OrgEventDraft(
                        OrgEventType.MCP_STDIO_FAILED, "personal/$member", member,
                        JsonObject(mapOf("server" to JsonPrimitive("personal"), "memberId" to JsonPrimitive("$member"),
                            "state" to JsonPrimitive("backoff"), "failure" to JsonPrimitive("start_failed"))),
                    ),
                ),
                failed,
            )
            // Ready again: the failure it recovered from, which the new row no longer holds.
            val ready = sharedFailed.copy(state = "ready", failure = null, changedAt = Relay.epoch + 1.minutes)
            val recovery = HostedProcessChange(sharedFailed, ready, HostedProcessEvent.RECOVERED, "crashed")
            assertEquals(1, repository.applyStdioStates(Relay.orgA, listOf(recovery), emptyList()))
            val recovered = recorder.published.last().second.single()
            assertEquals(OrgEventType.MCP_STDIO_RECOVERED, recovered.type)
            assertEquals(
                JsonObject(mapOf("server" to JsonPrimitive("shared"), "state" to JsonPrimitive("ready"),
                    "failure" to JsonPrimitive("crashed"))),
                recovered.data,
            )
            // Another replica got there first: the row is left to it, and so is the event.
            assertEquals(0, repository.applyStdioStates(Relay.orgA, listOf(recovery), emptyList()))
            assertEquals(2, recorder.published.size, "No third publication for a lost compare-and-set")
            // A change without an event publishes nothing.
            val idle = ready.copy(state = "idle", changedAt = Relay.epoch + 2.minutes)
            val quiet = listOf(HostedProcessChange(ready, idle))
            assertEquals(1, repository.applyStdioStates(Relay.orgA, quiet, emptyList()))
            assertEquals(2, recorder.published.size)
        }

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
