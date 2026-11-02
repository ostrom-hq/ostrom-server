// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.compiler.CompilerModule
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.database.MigrationRunnerImpl
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.rules.domain.models.AnalysisKind
import dev.ostrom.server.rules.domain.models.AnalysisRun
import dev.ostrom.server.rules.domain.models.RuleQuery
import dev.ostrom.server.rules.domain.models.RuleStatus
import dev.ostrom.server.rules.domain.services.RepositoryReader
import dev.ostrom.server.rules.domain.usecases.AnalyzeOrganizationUseCaseImpl
import dev.ostrom.server.rules.fakes.FakeRepositoryReader
import dev.ostrom.server.rules.fakes.RecordingLogger
import dev.ostrom.server.rules.fakes.RepositoryFixtures
import dev.ostrom.server.rules.fakes.RulesCallers
import dev.ostrom.server.rules.fakes.RulesFixtures
import dev.ostrom.server.rules.fakes.RulesFixtures.orgA
import dev.ostrom.server.rules.fakes.RulesFixtures.orgB
import dev.ostrom.server.rules.fakes.TestClock
import dev.ostrom.server.rules.fakes.call
import dev.ostrom.server.rules.infrastructure.PostgresAnalysisRepository
import dev.ostrom.server.rules.infrastructure.PostgresRulesRepository
import dev.ostrom.server.rules.infrastructure.RepositoryAnalysisEngine
import dev.ostrom.server.rules.infrastructure.RulesMigrations
import dev.ostrom.server.rules.presentation.AnalysisStarted
import dev.ostrom.server.rules.presentation.AnalysisStateDto
import dev.ostrom.server.rules.presentation.DecisionsPage
import dev.ostrom.server.rules.presentation.RuleDto
import dev.ostrom.server.rules.presentation.RulesPage
import dev.ostrom.server.testing.TestDatabase
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
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

    private fun engineTest(block: suspend (TestServer) -> Unit) {
        val reader = FakeRepositoryReader().apply { connect(orgA, RepositoryFixtures.api, RepositoryFixtures.web) }
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

    @Test
    fun theEngineWritesUnderTheServingRoleOnlyForItsOrganization() = runTest {
        val database = TestDatabase.create() ?: return@runTest
        try {
            MigrationRunnerImpl(database.driver, RecordingLogger()).migrate("rules", RulesMigrations)
            database.withRole(poolSize = 1) { role, serving ->
                listOf("rules_rules", "rules_decisions", "rules_analyses").forEach {
                    database.driver.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON $it TO $role").getOrThrow()
                }
                val runs = PostgresAnalysisRepository(serving)
                val rules = PostgresRulesRepository(serving)
                val reader = FakeRepositoryReader().apply { connect(orgA, RepositoryFixtures.docs) }
                val analyze = AnalyzeOrganizationUseCaseImpl(runs, rules, reader, RecordingLogger(), TestClock())
                val run = AnalysisRun(
                    orgA, AnalysisKind.ANALYSIS, "run_1", AnalysisRun.CONNECT, 0.0, null, "lyes",
                    RulesFixtures.epoch, RulesFixtures.epoch,
                )
                runs.transaction(orgA) { put(run) }
                analyze(run, listOf(RepositoryFixtures.docs.repo))

                assertEquals(AnalysisRun.READY, runs.transaction(orgA) { lock(AnalysisKind.ANALYSIS) }?.phase)
                assertEquals(
                    listOf(RuleStatus.PROPOSED, RuleStatus.PROPOSED),
                    rules.rules(orgA, RuleQuery(), 10).map { it.status },
                )
                assertEquals(emptyList(), rules.rules(orgB, RuleQuery(), 10), "Org B reads none of A's proposals")
                assertEquals(null, runs.transaction(orgB) { lock(AnalysisKind.ANALYSIS) })
            }
        } finally {
            database.close()
        }
    }
}
