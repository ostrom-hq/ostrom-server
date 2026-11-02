// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.rules

import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.receipts.domain.repositories.ReceiptsRepository
import dev.ostrom.server.rules.domain.models.DecisionQuery
import dev.ostrom.server.rules.domain.models.RuleStatus
import dev.ostrom.server.rules.domain.repositories.RulesRepository
import dev.ostrom.server.rules.fakes.RulesCallers
import dev.ostrom.server.rules.fakes.RulesCallers.orgA
import dev.ostrom.server.rules.fakes.RulesCallers.orgB
import dev.ostrom.server.rules.fakes.RulesFixtures
import dev.ostrom.server.rules.fakes.TestClock
import dev.ostrom.server.rules.fakes.call
import dev.ostrom.server.rules.presentation.BulkRulesRequest
import dev.ostrom.server.rules.presentation.BulkRulesResponse
import dev.ostrom.server.rules.presentation.DecisionsPage
import dev.ostrom.server.rules.presentation.GateActionDto
import dev.ostrom.server.rules.presentation.GatePolicyDto
import dev.ostrom.server.rules.presentation.NewRuleRequest
import dev.ostrom.server.rules.presentation.RuleDto
import dev.ostrom.server.rules.presentation.RuleGateCheckDto
import dev.ostrom.server.rules.presentation.RuleGateDto
import dev.ostrom.server.rules.presentation.RulesPage
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** The rules, decisions and policy routes on a real PostgreSQL and real HTTP: who may call them, and org isolation. */
class RulesPostgresTest {
    private val clock = TestClock()
    private val modules = listOf(InstanceModule, ReceiptsModule, RulesModule)
    private val gate = RuleGateDto(listOf(GateActionDto("push-main", "Push to main")), listOf("lyes", "rev-b"))

    private fun rulesTest(block: suspend (TestServer) -> Unit) =
        serverTest(modules, RulesCallers.overrides(clock)) { server -> block(server) }

    private suspend fun TestServer.write(
        token: String = "admin-a",
        rule: NewRuleRequest = NewRuleRequest("Ship", "Releases"),
    ) = call("POST", "/api/v1/rules", token, rule)

    private suspend fun TestServer.decisions(token: String = "admin-a", query: String = "") =
        call("GET", "/api/v1/decisions$query", token).body<DecisionsPage>()

    @Test
    fun anAdminWritesDecidesAndDeletesARuleEachASignedDecision() = rulesTest { server ->
        val created = server.write(rule = NewRuleRequest("Push to main waits for a human", "Shipping", gate = gate))
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val rule = created.body<RuleDto>()
        assertEquals("accepted", rule.status)
        assertEquals("gate", rule.output)
        assertEquals("lyes", rule.decidedBy, "Named by the gates' login")
        assertEquals("2027-01-28", rule.reviewBy)
        assertEquals(gate, rule.gate)

        assertEquals(rule, server.call("GET", "/api/v1/rules/${rule.id}", "member-a").body<RuleDto>())
        assertEquals(listOf(rule), server.call("GET", "/api/v1/rules", "reader-a").body<RulesPage>().items)

        clock.advance(1.minutes)
        val edited = server.call(
            "PATCH",
            "/api/v1/rules/${rule.id}",
            "admin-a",
            buildJsonObject {
                put("statement", "Push to main waits for two humans")
                put("gate", JsonNull)
            },
        )
        assertEquals(HttpStatusCode.OK, edited.status, edited.bodyAsText())
        val now = edited.body<RuleDto>()
        assertEquals("edited", now.status)
        assertNull(now.gate, "null removes the gate")

        clock.advance(1.minutes)
        assertEquals(HttpStatusCode.NoContent, server.call("DELETE", "/api/v1/rules/${rule.id}", "admin-a").status)
        assertEquals(HttpStatusCode.NotFound, server.call("GET", "/api/v1/rules/${rule.id}", "admin-a").status)

        val page = server.decisions()
        assertEquals(listOf("deleted", "edited", "accepted"), page.items.map { it.action })
        assertTrue(page.items.all { it.subject.kind == "rule" && it.subject.id == rule.id && it.by == "lyes" })
        assertNull(page.next)
        val receipts = server.koin.get<ReceiptsRepository>()
        page.items.forEach { decision ->
            val receipt = assertNotNull(receipts.get(orgA, decision.receiptId), "Each decision has its receipt")
            assertEquals("rule-change", receipt.subjectKind)
            assertEquals(rule.id, receipt.subjectId)
        }
        assertTrue(server.koin.get<RulesRepository>().decisions(orgA, DecisionQuery()).all { it.signed })
        assertEquals(
            listOf("edited"),
            server.decisions(query = "?subjectKind=rule&subjectId=${rule.id}&before=${page.items[0].id}")
                .items.take(1).map { it.action },
        )
    }

    @Test
    fun aGateKeepsTheChecksItNeedsAndTheirAttestation() = rulesTest { server ->
        // Written as a client sends it: `attested` left out means a declared run will do.
        val body = Json.parseToJsonElement(
            """{"statement":"Tests run by Ostrom or the CI before a push to main","group":"git",""" +
                """"scope":["acme/api"],"gate":{"actions":[{"class":"push-main","label":"Push to main",""" +
                """"patterns":[],"repos":["acme/api"]}],"approvers":[],""" +
                """"checks":[{"check":"tests","attested":true},{"check":"lint"}]}}""",
        )
        val created = server.call("POST", "/api/v1/rules", "admin-a", body)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val served = """"checks":[{"check":"tests","attested":true},{"check":"lint","attested":false}]"""
        assertTrue(served in created.bodyAsText(), created.bodyAsText())
        val rule = created.body<RuleDto>()
        assertEquals(listOf(RuleGateCheckDto("tests", true), RuleGateCheckDto("lint", false)), rule.gate?.checks)

        assertEquals(rule, server.call("GET", "/api/v1/rules/${rule.id}", "member-a").body<RuleDto>())
        assertEquals(listOf(rule), server.call("GET", "/api/v1/rules", "reader-a").body<RulesPage>().items)
        val decided = server.decisions(query = "?subjectKind=rule&subjectId=${rule.id}").items.single()
        assertTrue(""""attested":true""" in decided.after.toString(), "The signed decision shows the attestation")
    }

    @Test
    fun theConsoleFiltersAndDecidesTheSureOnesAtOnce() = rulesTest { server ->
        val repository = server.koin.get<RulesRepository>()
        repository.transaction(orgA) {
            insertRule(RulesFixtures.rule("rule_1", group = "Reviews", scope = listOf("ostrom-hq/web")))
            insertRule(RulesFixtures.rule("rule_2", group = "Releases", scope = listOf("ostrom-hq/server/app")))
            insertRule(RulesFixtures.rule("rule_3", group = "Releases"))
        }
        repository.transaction(orgB) { insertRule(RulesFixtures.rule("rule_1", orgB)) }

        suspend fun ids(query: String) =
            server.call("GET", "/api/v1/rules$query", "admin-a").body<RulesPage>().items.map { it.id }
        assertEquals(listOf("rule_2", "rule_3", "rule_1"), ids(""))
        assertEquals(listOf("rule_2", "rule_3"), ids("?group=Releases"))
        assertEquals(listOf("rule_3", "rule_1"), ids("?repo=OSTROM-HQ/web"), "Case ignored; no scope covers all")
        assertEquals(listOf("rule_2", "rule_3"), ids("?repo=ostrom-hq/server"), "A path under the repository")
        assertEquals(emptyList(), ids("?status=accepted"))

        val bulk = server.call(
            "POST",
            "/api/v1/rules/bulk",
            "admin-a",
            BulkRulesRequest(listOf("rule_1", "rule_2", "rule_x"), "accepted"),
        )
        assertEquals(BulkRulesResponse(2), bulk.body<BulkRulesResponse>())
        assertEquals(listOf("rule_2", "rule_1"), ids("?status=accepted"))
        assertEquals(RuleStatus.PROPOSED, repository.rule(orgB, "rule_1")?.status, "Org B's rule_1 untouched")
        assertEquals(
            HttpStatusCode.BadRequest,
            server.call("POST", "/api/v1/rules/bulk", "admin-a", BulkRulesRequest(listOf("rule_1"), "edited")).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            server.call("POST", "/api/v1/rules/bulk", "admin-a", BulkRulesRequest(listOf("rule_1"), "maybe")).status,
        )
        assertEquals(HttpStatusCode.BadRequest, server.call("GET", "/api/v1/rules?status=maybe", "admin-a").status)
    }

    @Test
    fun decisionsArePagedFiftyAtATime() = rulesTest { server ->
        repeat(51) { index ->
            clock.advance(1.minutes)
            server.write(rule = NewRuleRequest("Rule $index", "Many"))
        }
        val first = server.decisions()
        assertEquals(50, first.items.size)
        val next = assertNotNull(first.next)
        val second = server.decisions(query = "?before=$next")
        assertEquals(listOf("Rule 0"), second.items.map { it.subject.label })
        assertNull(second.next)
    }

    @Test
    fun thePolicyIsReadByMembersAndSetByAdminsAsASignedDecision() = rulesTest { server ->
        val path = "/api/v1/gates/policy"
        assertEquals(GatePolicyDto("sole-approver"), server.call("GET", path, "gates-reader-a").body<GatePolicyDto>())
        val set = server.call("PUT", path, "admin-a", GatePolicyDto("never"))
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())
        assertEquals(GatePolicyDto("never"), set.body<GatePolicyDto>())
        assertEquals(GatePolicyDto("never"), server.call("GET", path, "member-a").body<GatePolicyDto>())
        assertEquals(GatePolicyDto("sole-approver"), server.call("GET", path, "owner-b").body<GatePolicyDto>())
        server.call("PUT", path, "admin-a", GatePolicyDto("never"))
        val decision = server.decisions(query = "?subjectKind=policy").items.single()
        assertEquals("self-approval", decision.subject.id)
        assertEquals("policy", server.koin.get<ReceiptsRepository>().get(orgA, decision.receiptId)?.subjectKind)
        assertEquals(HttpStatusCode.BadRequest, server.call("PUT", path, "admin-a", GatePolicyDto("always")).status)
    }

    @Test
    fun aDeciderWithoutALoginIsNamedByTheirMembership() = rulesTest { server ->
        // Unknown to the gates, or known to them without a person: either way the membership names the decider.
        listOf("nameless-a", "unlinked-a").forEach { token ->
            val rule = server.write(token).body<RuleDto>()
            assertEquals("0000000a-0000-0000-0000-0000000000a1", rule.decidedBy, token)
        }
    }

    @Test
    fun anotherOrganizationNeitherSeesNorChangesTheRules() = rulesTest { server ->
        val rule = server.write().body<RuleDto>()
        server.call("PUT", "/api/v1/gates/policy", "admin-a", GatePolicyDto("never"))
        val path = "/api/v1/rules/${rule.id}"
        assertEquals(HttpStatusCode.NotFound, server.call("GET", path, "owner-b").status)
        assertEquals(emptyList(), server.call("GET", "/api/v1/rules", "owner-b").body<RulesPage>().items)
        assertEquals(emptyList(), server.decisions("owner-b").items)
        val patch = buildJsonObject { put("status", "rejected") }
        assertEquals(HttpStatusCode.NotFound, server.call("PATCH", path, "owner-b", patch).status)
        assertEquals(HttpStatusCode.NotFound, server.call("DELETE", path, "owner-b").status)
        assertEquals(
            BulkRulesResponse(0),
            server.call("POST", "/api/v1/rules/bulk", "owner-b", BulkRulesRequest(listOf(rule.id), "rejected"))
                .body<BulkRulesResponse>(),
        )
        assertEquals(rule, server.call("GET", path, "admin-a").body<RuleDto>(), "Unchanged")
        assertEquals(2, server.decisions().items.size, "Org A's own decisions only")
    }

    @Test
    fun withoutATokenEveryRouteAnswers401() = rulesTest { server ->
        routes.forEach { (method, path, body) ->
            assertEquals(HttpStatusCode.Unauthorized, server.call(method, path, null, body).status, "$method $path")
            assertEquals(HttpStatusCode.Unauthorized, server.call(method, path, "forged", body).status, "$method $path")
        }
    }

    @Test
    fun onlyAnAdminInTheConsoleWritesAndOnlyAReaderOrMemberReads() = rulesTest { server ->
        val id = server.write().body<RuleDto>().id
        writes(id).forEach { (method, path, body) ->
            listOf("member-a", "reader-a", "other-a", "agent-a", "device-a").forEach { token ->
                val status = server.call(method, path, token, body).status
                assertEquals(HttpStatusCode.Forbidden, status, "$token $method $path")
            }
            assertEquals(HttpStatusCode.NotFound, server.call(method, path, "stranger", body).status, "$method $path")
        }
        reads(id).forEach { (method, path, _) ->
            listOf("other-a", "agent-a", "device-a").forEach { token ->
                assertEquals(HttpStatusCode.Forbidden, server.call(method, path, token).status, "$token $method $path")
            }
            assertEquals(HttpStatusCode.NotFound, server.call(method, path, "stranger").status, "$method $path")
            assertEquals(HttpStatusCode.OK, server.call(method, path, "reader-a").status, "$method $path")
        }
        assertEquals(HttpStatusCode.Forbidden, server.call("GET", "/api/v1/rules", "gates-reader-a").status)
        assertEquals(HttpStatusCode.NotFound, server.call("GET", "/api/v1/rules/rule_unknown", "admin-a").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            server.call("PATCH", "/api/v1/rules/$id", "admin-a", buildJsonObject { put("strength", "must") }).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            server.write(rule = NewRuleRequest("  ", "Releases")).status,
        )
    }

    private data class Route(val method: String, val path: String, val body: Any? = null)

    private fun reads(id: String) = listOf(
        Route("GET", "/api/v1/rules"),
        Route("GET", "/api/v1/rules/$id"),
        Route("GET", "/api/v1/decisions"),
        Route("GET", "/api/v1/gates/policy"),
    )

    private fun writes(id: String) = listOf(
        Route("POST", "/api/v1/rules", NewRuleRequest("Ship", "Releases")),
        Route("PATCH", "/api/v1/rules/$id", buildJsonObject { put("status", "rejected") }),
        Route("DELETE", "/api/v1/rules/$id"),
        Route("POST", "/api/v1/rules/bulk", BulkRulesRequest(listOf(id), "rejected")),
        Route("PUT", "/api/v1/gates/policy", GatePolicyDto("never")),
    )

    private val routes = reads("rule_1") + writes("rule_1")
}
