// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.rules

import run.ostrom.core.api.models.WorkflowNodeKind
import run.ostrom.core.receipts.Digests
import run.ostrom.core.api.models.WorkflowStatus
import run.ostrom.server.compiler.CompilerModule
import run.ostrom.server.compiler.domain.repositories.CompilerRepository
import run.ostrom.server.compiler.domain.services.CompilerDocs
import run.ostrom.server.compiler.domain.usecases.WorkflowsUseCase
import run.ostrom.server.gates.GatesModule
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.receipts.ReceiptsModule
import run.ostrom.server.rules.domain.models.AnalysisKind
import run.ostrom.server.rules.domain.models.AnalysisRun
import run.ostrom.server.rules.domain.models.RuleQuery
import run.ostrom.server.rules.domain.models.RuleStatus
import run.ostrom.server.rules.domain.services.RepositoryReader
import run.ostrom.server.rules.domain.usecases.AnalyzeOrganizationUseCaseImpl
import run.ostrom.server.rules.fakes.FakeRepositoryReader
import run.ostrom.server.rules.fakes.FakeWorkflowProposals
import run.ostrom.server.rules.fakes.RecordingLogger
import run.ostrom.server.rules.fakes.RepositoryFixture
import run.ostrom.server.rules.fakes.RepositoryFixtures
import run.ostrom.server.rules.fakes.RulesCallers
import run.ostrom.server.rules.fakes.RulesFixtures
import run.ostrom.server.rules.fakes.RulesFixtures.orgA
import run.ostrom.server.rules.fakes.RulesFixtures.orgB
import run.ostrom.server.rules.fakes.TestClock
import run.ostrom.server.rules.fakes.call
import run.ostrom.server.rules.infrastructure.PostgresAnalysisRepository
import run.ostrom.server.rules.infrastructure.PostgresRulesRepository
import run.ostrom.server.rules.infrastructure.RepositoryAnalysisEngine
import run.ostrom.server.rules.infrastructure.RulesMigrations
import run.ostrom.server.rules.presentation.AnalysisStarted
import run.ostrom.server.rules.presentation.AnalysisStateDto
import run.ostrom.server.rules.presentation.DecisionsPage
import run.ostrom.server.rules.presentation.RuleDto
import run.ostrom.server.rules.presentation.RulesPage
import run.ostrom.server.testing.TestDatabase
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The deterministic engine on a real PostgreSQL behind real HTTP: an analysis reads the organization's repositories,
 * proposes learned rules the team accepts through the rules routes, a new run leaves the accepted rule alone, and
 * another organization sees none of it. The last test runs the engine under the serving role, as a deployment does.
 */
class AnalysisEnginePostgresTest {
    private val modules = listOf(InstanceModule, GatesModule, ReceiptsModule, CompilerModule, RulesModule)
    private val branch = "rule_learned_96cd39a4b8216a1472494c1f"

    private fun engineTest(
        repos: List<RepositoryFixture> = listOf(RepositoryFixtures.api, RepositoryFixtures.web),
        block: suspend (TestServer) -> Unit,
    ) {
        val reader = FakeRepositoryReader().apply { connect(orgA, *repos.toTypedArray()) }
        val overrides = module {
            includes(RulesCallers.overrides(), RulesCallers.gateSessions)
            single<RepositoryReader> { reader }
        }
        serverTest(modules, overrides) { server -> block(server) }
    }

    private suspend fun TestServer.analyse(path: String = "start"): String {
        val response = call("POST", "/api/v1/analysis/$path", "admin-a")
        assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
        koin.get<RepositoryAnalysisEngine>().runNext()
        return response.body<AnalysisStarted>().runId
    }

    private suspend fun TestServer.state(token: String = "admin-a"): AnalysisStateDto =
        call("GET", "/api/v1/analysis/state", token).body()

    private suspend fun TestServer.proposals(token: String = "admin-a"): List<RuleDto> =
        call("GET", "/api/v1/rules?status=proposed", token).body<RulesPage>().items

    @Test
    fun anAnalysisProposesLearnedRulesTheTeamAccepts() = engineTest { server ->
        server.analyse()
        val ready = server.state("reader-a")
        assertEquals("ready", ready.phase)
        assertEquals(1.0, ready.progress)
        assertEquals(listOf("web", "api"), ready.repos.map { (it.getValue("name") as JsonPrimitive).content })
        val proposed = server.proposals()
        assertEquals(15, proposed.size, "9 for api, 6 for web")
        assertTrue(proposed.all { it.origin == "learned" && it.foundBy == "analysis" })
        assertEquals(emptyList(), server.call("GET", "/api/v1/decisions", "admin-a").body<DecisionsPage>().items)

        val accept = server.call(
            "PATCH",
            "/api/v1/rules/$branch",
            "admin-a",
            buildJsonObject { put("status", "accepted") },
        )
        assertEquals(HttpStatusCode.OK, accept.status, accept.bodyAsText())
        assertEquals("accepted", accept.body<RuleDto>().status)
        val accepted = server.call("GET", "/api/v1/rules/$branch", "admin-a").body<RuleDto>()
        assertEquals("gate", accepted.output)
        assertEquals("lyes", accepted.decidedBy)
        val decisions = server.call("GET", "/api/v1/decisions", "admin-a").body<DecisionsPage>().items
        assertEquals(listOf(branch), decisions.map { it.subject.id }, "Accepting signs one decision")

        server.analyse("restart")
        assertEquals("ready", server.state().phase)
        val after = server.call("GET", "/api/v1/rules/$branch", "admin-a").body<RuleDto>()
        assertEquals(accepted, after, "A new run leaves the accepted rule alone")
        assertEquals(14, server.proposals().size, "The other proposals stay, none twice")
    }

    @Test
    fun anotherOrganizationSeesNoneOfTheProposalsNorCanDecideThem() = engineTest { server ->
        server.analyse()
        assertEquals(emptyList(), server.proposals("owner-b"))
        assertEquals(emptyList(), server.state("owner-b").repos)
        val foreign = server.call(
            "PATCH",
            "/api/v1/rules/$branch",
            "owner-b",
            buildJsonObject { put("status", "accepted") },
        )
        assertEquals(HttpStatusCode.NotFound, foreign.status, foreign.bodyAsText())
        assertEquals("proposed", server.call("GET", "/api/v1/rules/$branch", "admin-a").body<RuleDto>().status)

        val unconnected = server.call("POST", "/api/v1/analysis/start", "owner-b")
        assertEquals(HttpStatusCode.Accepted, unconnected.status)
        assertEquals("github", server.state("owner-b").failure?.get("stage"), "Org B has no GitHub App")
    }

    private val app = RepositoryFixture(
        RepositoryFixtures.ref("app"),
        listOf(".github/workflows/ci.yml", "CLAUDE.md", "Makefile"),
        mapOf(
            ".github/workflows/ci.yml" to "on: pull_request\njobs:\n  test:\n    steps:\n      - run: make test\n",
            "CLAUDE.md" to "# Ship\n1. Run `make test`\n2. Tag it\n",
            "Makefile" to "test:\n\techo\nrelease:\n\techo\n",
        ),
    )

    // Decision "one workflow system", section 4: an analysis proposes the processes as workflows, never decided, and a
    // later analysis never rewrites one, even one still proposed.
    @Test
    fun anAnalysisProposesTheProcessesAsWorkflowsAndNeverRewritesThem() = engineTest(listOf(app)) { server ->
        server.analyse()
        val state = server.state("reader-a")
        assertEquals(JsonPrimitive(3), state.repos.single()["processes"])
        val workflows = state.workflows.map { CompilerDocs.workflow(JsonObject(it - "version" - "updatedBy")).workflow }
        assertEquals(
            listOf("Pull request checks (app)", "Release (app)", "Ship (app, CLAUDE.md)"),
            workflows.map { it.name }.sorted(),
        )
        assertTrue(workflows.all { it.id.startsWith("wf_learned_") && it.status == WorkflowStatus.PROPOSED })
        assertTrue(workflows.all { it.repos == listOf("acme/app") })
        val checks = workflows.single { it.name == "Pull request checks (app)" }
        val gate = checks.nodes.single { it.kind == WorkflowNodeKind.GATE }
        val branch = "rule_learned_" + Digests.sha256Hex("branch:app".encodeToByteArray()).take(24)
        assertEquals(listOf(branch), gate.ruleIds)
        assertEquals("proposed", server.call("GET", "/api/v1/rules/$branch", "admin-a").body<RuleDto>().status)
        val compiler = server.koin.get<CompilerRepository>()
        assertEquals(emptyList(), compiler.lastDecisions(orgA), "A proposal is not a decision: nothing is signed")

        // A person edits a proposal; the next analysis leaves it as it is and adds nothing twice.
        val stored = compiler.workflow(orgA, checks.id) ?: error("stored")
        val edited = JsonObject(stored.doc.raw + ("name" to JsonPrimitive("Our checks")))
        server.koin.get<WorkflowsUseCase>().save(orgA, edited, stored.version, "lyes", create = false)
        server.analyse("restart")
        val after = server.state().workflows
        assertEquals(3, after.size)
        assertEquals(JsonPrimitive("Our checks"), after.first { it["id"] == JsonPrimitive(checks.id) }["name"])
        assertEquals(emptyList(), server.state("owner-b").workflows, "Org B sees none of A's proposals")
    }

    @Test
    fun theEngineWritesUnderTheServingRoleOnlyForItsOrganization() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            // The owner migrates, as `ostrom-server migrate` does; the test database's driver is the serving role.
            database.migrationRunner(RecordingLogger()).migrate("rules", RulesMigrations)
            val runs = PostgresAnalysisRepository(database.driver)
            val rules = PostgresRulesRepository(database.driver)
            val reader = FakeRepositoryReader().apply { connect(orgA, RepositoryFixtures.docs) }
            val analyze = AnalyzeOrganizationUseCaseImpl(
                runs, rules, reader, FakeWorkflowProposals(), RecordingLogger(), TestClock(),
            )
            val run = AnalysisRun(
                orgA, AnalysisKind.ANALYSIS, "run_1", AnalysisRun.CONNECT, 0.0, null, "lyes",
                RulesFixtures.epoch, RulesFixtures.epoch,
            )
            runs.transaction(orgA) {
                lock(AnalysisKind.ANALYSIS)
                put(run)
            }
            // Real time: the database answers on its own threads, and virtual time would reach the deadline first.
            withContext(Dispatchers.Default) { analyze(run, listOf(RepositoryFixtures.docs.repo)) }

            assertEquals(AnalysisRun.READY, runs.run(orgA, AnalysisKind.ANALYSIS)?.phase)
            assertEquals(
                listOf(RuleStatus.PROPOSED, RuleStatus.PROPOSED),
                rules.rules(orgA, RuleQuery(), 10).map { it.status },
            )
            assertEquals(emptyList(), rules.rules(orgB, RuleQuery(), 10), "Org B reads none of A's proposals")
            assertEquals(null, runs.run(orgB, AnalysisKind.ANALYSIS))
        } finally {
            database.close()
        }
    }
}
