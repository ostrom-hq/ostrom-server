// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.compiler.CompilerModule
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.gates.domain.repositories.GatesRepository
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.rules.domain.repositories.RulesRepository
import dev.ostrom.server.rules.domain.services.GateReplay
import dev.ostrom.server.rules.fakes.ReplayFixtures.action
import dev.ostrom.server.rules.fakes.ReplayFixtures.gated
import dev.ostrom.server.rules.fakes.ReplayFixtures.push
import dev.ostrom.server.rules.fakes.RulesCallers
import dev.ostrom.server.rules.fakes.RulesCallers.orgA
import dev.ostrom.server.rules.fakes.RulesCallers.orgB
import dev.ostrom.server.rules.fakes.call
import dev.ostrom.server.rules.infrastructure.RepositoryAnalysisEngine
import dev.ostrom.server.rules.presentation.ReplayPage
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The replay on a real PostgreSQL, through the routes and the engine's worker, under the serving role: each
 * organization's rules are replayed on its own boundary actions only, and it reads only its own results.
 */
class ReplayPostgresTest {
    private val modules = listOf(InstanceModule, GatesModule, ReceiptsModule, CompilerModule, RulesModule)

    private fun replayTest(block: suspend (TestServer) -> Unit) =
        serverTest(modules, module { includes(RulesCallers.overrides(), RulesCallers.gateSessions) }) { block(it) }

    private suspend fun TestServer.replay(token: String): List<JsonObject> {
        val started = call("POST", "/api/v1/analysis/replay", token)
        assertEquals(HttpStatusCode.Accepted, started.status, started.bodyAsText())
        koin.get<RepositoryAnalysisEngine>().runNext()
        val page = call("GET", "/api/v1/analysis/replay", token)
        assertEquals(HttpStatusCode.OK, page.status, page.bodyAsText())
        return page.body<ReplayPage>().items
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun eachOrganizationReplaysItsRulesOnItsOwnBoundaryActionsOnly() = replayTest { server ->
        val gates = server.koin.get<GatesRepository>()
        val mergeA = action(orgId = orgA, subject = push(cls = "merge"), hoursAgo = 1)
        val pushA = action(orgId = orgA, hoursAgo = 2)
        val pushB = action(orgId = orgB, subject = push(repo = "globex/secret"), askedBy = listOf("bob"))
        gates.transaction {
            insertGate(mergeA)
            insertGate(pushA)
        }
        gates.transaction { insertGate(pushB) }
        val rules = server.koin.get<RulesRepository>()
        rules.transaction(orgA) { insertRule(gated(id = "rule_a", orgId = orgA, approvers = listOf("bob"))) }
        rules.transaction(orgB) { insertRule(gated(id = "rule_b", orgId = orgB, cls = "merge")) }

        val itemsA = server.replay("admin-a")
        assertEquals(listOf(mergeA.id.toString(), pushA.id.toString()), itemsA.map { it.text("gateId") })
        assertEquals(listOf(GateReplay.PASSED, GateReplay.ASKED), itemsA.map { it.text("outcome") })
        assertEquals("acme/api", itemsA.last().text("repo"))

        val itemsB = server.replay("owner-b")
        assertEquals(listOf(pushB.id.toString()), itemsB.map { it.text("gateId") }, "B replays only its own action")
        assertEquals(GateReplay.PASSED, itemsB.single().text("outcome"), "A's rule never gates B's action")
        assertEquals(itemsA, server.call("GET", "/api/v1/analysis/replay", "admin-a").body<ReplayPage>().items)
    }
}
