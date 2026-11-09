// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.rules

import run.ostrom.core.api.models.Workflow
import run.ostrom.core.api.models.WorkflowNode
import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.compiler.CompilerModule
import run.ostrom.server.compiler.domain.usecases.WorkflowsUseCase
import run.ostrom.server.gates.GatesModule
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.rules.domain.models.AnalysisKind
import run.ostrom.server.rules.domain.repositories.AnalysisRepository
import run.ostrom.server.rules.domain.services.AnalysisEngine
import run.ostrom.server.rules.fakes.FakeAnalysisEngine
import run.ostrom.server.rules.fakes.RulesCallers
import run.ostrom.server.rules.fakes.RulesCallers.orgA
import run.ostrom.server.rules.fakes.call
import run.ostrom.server.rules.infrastructure.NoRepositoryReader
import run.ostrom.server.rules.infrastructure.RepositoryAnalysisEngine
import run.ostrom.server.rules.presentation.AnalysisStarted
import run.ostrom.server.rules.presentation.AnalysisStateDto
import run.ostrom.server.rules.presentation.NewRuleRequest
import run.ostrom.server.rules.presentation.ReplayPage
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The analysis routes on a real PostgreSQL and real HTTP: a run without GitHub fails where the console sees it, a
 * run in progress refuses a second one, the snapshot carries the rules and workflows, and each organization sees only
 * its own runs.
 */
class AnalysisPostgresTest {
    private val modules = listOf(InstanceModule, GatesModule, ReceiptsModule, CompilerModule, RulesModule)

    private fun analysisTest(engine: AnalysisEngine? = null, block: suspend (TestServer) -> Unit) {
        val overrides: Module = module {
            includes(RulesCallers.overrides(), RulesCallers.gateSessions)
            if (engine != null) single<AnalysisEngine> { engine }
        }
        serverTest(modules, overrides) { server -> block(server) }
    }

    private suspend fun TestServer.start(path: String, token: String = "admin-a"): String {
        val response = call("POST", "/api/v1/analysis/$path", token)
        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        return response.body<AnalysisStarted>().runId
    }

    private suspend fun TestServer.state(token: String = "admin-a"): AnalysisStateDto {
        val response = call("GET", "/api/v1/analysis/state", token)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body()
    }

    @Test
    fun withoutGitHubAnAnalysisFailsVisiblyAndCanBeRestarted() = analysisTest { server ->
        val before = server.state()
        assertNull(before.phase, "Never analysed")
        assertNull(before.failure)

        val runId = server.start("start")
        assertTrue(runId.startsWith("run_"))
        val failed = server.state()
        assertEquals("failed", failed.phase)
        assertEquals("github", failed.failure?.get("stage"))
        assertEquals(NoRepositoryReader.REASON, failed.failure?.get("reason"))
        assertEquals("/api/v1/analysis/restart", failed.failure?.get("retryPath"))

        val again = server.start("restart")
        assertTrue(again != runId, "A new run")
        val replay = server.start("replay")
        assertTrue(replay.startsWith("run_"))
        // Queued: a replay needs no GitHub. Without a gated rule, it fails where the console sees it.
        server.koin.get<RepositoryAnalysisEngine>().runNext()
        val replayed = server.koin.get<AnalysisRepository>().transaction(orgA) { lock(AnalysisKind.REPLAY) }
        assertEquals("engine", replayed?.failure?.stage)
        assertEquals("/api/v1/analysis/replay", replayed?.failure?.retryPath)
        assertTrue("No rule of the organization gates" in replayed?.failure?.reason.orEmpty())
        val page = server.call("GET", "/api/v1/analysis/replay", "reader-a").body<ReplayPage>()
        assertEquals(emptyList(), page.items)
    }

    @Test
    fun aRunningAnalysisRefusesASecondOneButNotAReplayNorAnotherOrganization() =
        analysisTest(FakeAnalysisEngine()) { server ->
            server.start("start")
            assertEquals("connect", server.state().phase)
            val second = server.call("POST", "/api/v1/analysis/restart", "admin-a")
            assertEquals(HttpStatusCode.Conflict, second.status, second.bodyAsText())
            server.start("replay")
            assertEquals(HttpStatusCode.Conflict, server.call("POST", "/api/v1/analysis/replay", "admin-a").status)

            assertNull(server.state("owner-b").phase, "Org B sees none of A's runs")
            server.start("start", "owner-b")
            assertEquals("connect", server.state("owner-b").phase)
        }

    @Test
    fun theSnapshotCarriesTheRulesAndWorkflowsOfItsOrganizationAndTheReplayItsItems() = analysisTest { server ->
        val rule = server.call("POST", "/api/v1/rules", "admin-a", NewRuleRequest("Ship on Fridays", "Shipping"))
        assertEquals(HttpStatusCode.Created, rule.status, rule.bodyAsText())
        val workflow = Workflow(
            id = "release",
            name = "Release",
            domain = "Shipping",
            nodes = listOf(WorkflowNode("g", WorkflowNodeKind.GATE, "Push to main", approvers = listOf("lyes"))),
            edges = emptyList(),
            status = WorkflowStatus.ACCEPTED,
            foundAt = "2026-10-30T12:00:00.000Z",
        )
        val raw = Json.encodeToJsonElement(Workflow.serializer(), workflow).jsonObject
        server.koin.get<WorkflowsUseCase>().save(orgA, raw, null, "lyes", create = true)

        val state = server.state("reader-a")
        assertEquals(listOf("Ship on Fridays"), state.rules.map { it.statement })
        assertEquals(listOf(JsonPrimitive("release")), state.workflows.map { it.getValue("id") })
        assertEquals(emptyList(), state.repos)
        val other = server.state("owner-b")
        assertEquals(emptyList(), other.rules, "Org B sees none of A's rules")
        assertEquals(emptyList(), other.workflows, "Nor its workflows")

        val runs = server.koin.get<AnalysisRepository>()
        val item = JsonObject(mapOf("judged" to JsonPrimitive(false)))
        server.start("replay")
        runs.transaction(orgA) {
            val replay = lock(AnalysisKind.REPLAY)
            if (replay != null) put(replay.copy(phase = "ready", items = listOf(item)))
        }
        assertEquals(listOf(item), server.call("GET", "/api/v1/analysis/replay", "admin-a").body<ReplayPage>().items)
        assertEquals(emptyList(), server.call("GET", "/api/v1/analysis/replay", "owner-b").body<ReplayPage>().items)
    }
}
