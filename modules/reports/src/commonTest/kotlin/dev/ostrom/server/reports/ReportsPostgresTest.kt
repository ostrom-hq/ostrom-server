// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports

import dev.ostrom.server.budgets.BudgetsModule
import dev.ostrom.server.budgets.domain.BudgetGuard
import dev.ostrom.server.budgets.domain.BudgetLimit
import dev.ostrom.server.budgets.domain.BudgetTarget
import dev.ostrom.server.budgets.domain.BudgetsRepository
import dev.ostrom.server.compiler.CompilerModule
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.orgs.OrgsModule
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.reports.presentation.ActivityPageDto
import dev.ostrom.server.reports.presentation.SpendingDto
import dev.ostrom.server.reports.presentation.SpendingLineDto
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.sessions.SessionsModule
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import dev.ostrom.server.usage.UsageModule
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Real PostgreSQL and real HTTP: the spending report and the activity feed read the real tables of the sessions,
 * gates, compiler and usage modules, under their row level security; what each role sees; nothing of another
 * organization; 401, 403 and 404.
 */
class ReportsPostgresTest {
    private val clock = FixedClock()
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, CompilerModule,
        SecretsModule, UsageModule, BudgetsModule, ReportsModule,
    )
    private val approved = Uuid.parse("00000000-0000-0000-0000-0000000000a1")
    private val rejected = Uuid.parse("00000000-0000-0000-0000-0000000000a2")
    private val pending = Uuid.parse("00000000-0000-0000-0000-0000000000a3")

    private fun reportsTest(block: suspend (TestServer) -> Unit) =
        serverTest(modules, reportsOverrides(clock)) { server ->
            server.seedPeople()
            server.seed()
            block(server)
        }

    private suspend fun TestServer.seed() {
        // Org A: mia's ended claude session in acme/api, olga's running codex session in acme/web.
        session(SeedSession(ORG_A, "s1", "claude-code", "acme/api", "mia", NOW - 3.hours, NOW - 2.hours, costUsd = 0.5))
        session(SeedSession(ORG_A, "s2", "codex", "acme/web", "olga", NOW - 30.minutes))
        spend(ORG_A, MIA.memberId, "s1", 300_000, NOW - 150.minutes)
        spend(ORG_A, MIA.memberId, "s1", 200_000, NOW - 140.minutes)
        spend(ORG_A, OLGA.memberId, "s2", 1_000_000, NOW - 10.minutes)
        // The organization's own call outside any session, and a call of last month.
        spend(ORG_A, null, null, 50_000, NOW - 5.minutes)
        spend(ORG_A, MIA.memberId, "s1", 7_000_000, NOW - 40.days)
        gate(SeedGate(
            ORG_A, approved, "Merge PR #6", "APPROVED", NOW - 40.minutes, OLGA.userId.toString(), NOW - 15.minutes,
            repo = "acme/api",
        ))
        // Decided under the id of org B's person: org A never learns her login.
        gate(SeedGate(
            ORG_A, rejected, "Deploy", "REJECTED", NOW - 20.minutes, CAROL.userId.toString(), NOW - 3.minutes,
            repo = "acme/web", realRepo = "acme/infra",
        ))
        gate(SeedGate(ORG_A, pending, "Publish", "PENDING", NOW - 1.minutes))
        // A gate that only watched shows nothing.
        gate(SeedGate(ORG_A, Uuid.random(), "Shadow", "SHADOW", NOW - 2.minutes))
        decision(SeedDecision(ORG_A, "d1", "workflow", "release", OLGA.userId.toString(), NOW - 25.minutes))
        decision(SeedDecision(ORG_A, "d2", "package", "acme", OLGA.userId.toString(), NOW - 24.minutes))
        // Org B: everything that must never show in A.
        session(SeedSession(
            ORG_B, "sb", "claude-code", "acme/api", "carol", NOW - 1.hours, NOW - 50.minutes, costUsd = 9.0,
        ))
        spend(ORG_B, CAROL.memberId, "sb", 9_000_000, NOW - 55.minutes)
        gate(SeedGate(
            ORG_B, Uuid.random(), "Merge B", "APPROVED", NOW - 45.minutes, CAROL.userId.toString(), NOW - 44.minutes,
            repo = "beta/secret",
        ))
        decision(SeedDecision(ORG_B, "db", "workflow", "b-flow", CAROL.userId.toString(), NOW - 43.minutes))
        val budgets = koin.get<BudgetsRepository>()
        budgets.replaceLimits(ORG_A, BudgetTarget.Org, listOf(BudgetLimit(100_000_000)), NOW)
        budgets.replaceLimits(ORG_A, BudgetTarget.DefaultMember, listOf(BudgetLimit(20_000_000)), NOW)
    }

    private suspend fun TestServer.read(token: String?, path: String): HttpResponse =
        client.get(path) { token?.let { bearerAuth(it) } }

    @Test
    fun anOwnerSeesTheWholeOrganizationsMonthAgainstItsBudget() = reportsTest { server ->
        val report = server.read("olga", "/api/v1/spending").body<SpendingDto>()

        assertEquals("month", report.period)
        assertEquals("2026-10-01T00:00:00Z", report.from)
        assertEquals(NOW.toString(), report.to)
        assertEquals(1.55, report.totalUsd, 1e-9)
        assertEquals(100.0, report.budgetUsd)
        assertEquals(
            listOf(
                SpendingLineDto("codex", "codex", 1.0, 1),
                SpendingLineDto("claude-code", "claude-code", 0.5, 1),
                SpendingLineDto("", "No session", 0.05, 0),
            ),
            report.byAgent,
        )
        assertEquals(listOf("acme/web", "acme/api", ""), report.byRepo.map { it.key })
        assertEquals(
            listOf(
                SpendingLineDto(OLGA.memberId.toString(), "olga", 1.0, 1),
                SpendingLineDto(MIA.memberId.toString(), "mia", 0.5, 1),
                SpendingLineDto("", "Organization", 0.05, 0),
            ),
            report.byPerson,
        )
        assertEquals(30, report.daily.size)
        assertEquals(1.55, report.daily.last().usd, 1e-9)
        assertEquals(0.0, report.daily.first().usd)
    }

    @Test
    fun aModelCallThroughTheGatewayCountsForItsLaunchsAgentAndRepository() = reportsTest { server ->
        // A launch resumed once (two segments), and two calls of its agent through the gateway: the ledger names the
        // launch, not a segment (RELAY, server#126).
        for ((id, segment) in listOf("s9a" to 0, "s9b" to 1)) {
            server.sql(
                ORG_A,
                "INSERT INTO sessions_sessions (org_id, id, root_id, segment, launch_id, agent, dev, repo, " +
                    "started_at, last_seen_at) VALUES (:org, :id, 's9a', :segment, 'lch_9', 'gemini', 'mia', " +
                    "'acme/cli', :at, :at)",
                "org" to ORG_A, "id" to id, "segment" to segment, "at" to NOW - 20.minutes,
            )
        }
        for (micros in listOf(250_000L, 150_000L)) {
            server.sql(
                ORG_A,
                "INSERT INTO usage_events (id, org_id, member_id, kind, name, session_id, launch_id, input_tokens, " +
                    "output_tokens, cache_read_tokens, cache_write_tokens, cost_micros, duration_ms, outcome, at) " +
                    "VALUES (:id, :org, :member, 'MODEL', 'gemini-pro', 'lch_9', 'lch_9', 1, 1, 0, 0, :micros, 5, " +
                    "'ok', :at)",
                "id" to Uuid.random(), "org" to ORG_A, "member" to MIA.memberId, "micros" to micros,
                "at" to NOW - 8.minutes,
            )
        }
        val report = server.read("olga", "/api/v1/spending").body<SpendingDto>()
        assertEquals(1.95, report.totalUsd, 1e-9)
        assertEquals(SpendingLineDto("gemini", "gemini", 0.4, 1), report.byAgent.single { it.key == "gemini" })
        assertEquals(SpendingLineDto("acme/cli", "acme/cli", 0.4, 1), report.byRepo.single { it.key == "acme/cli" })
        assertEquals(0.05, report.byAgent.single { it.key == "" }.usd, 1e-9, "Only the call outside any session")
    }

    @Test
    fun aMemberSeesOnlyTheirOwnWeekAndATokenNeedsAReadScope() = reportsTest { server ->
        val week = server.read("mia", "/api/v1/spending?period=week").body<SpendingDto>()
        assertEquals("week", week.period)
        assertEquals(0.5, week.totalUsd, 1e-9)
        assertNull(week.budgetUsd)
        assertEquals(listOf(MIA.memberId.toString()), week.byPerson.map { it.key })
        assertEquals(7, week.daily.size)

        val month = server.read("spend", "/api/v1/spending").body<SpendingDto>()
        assertEquals(0.5, month.totalUsd, 1e-9)
        // The default member limit: the one the gateway stops mia on.
        assertEquals(20.0, month.budgetUsd)

        assertEquals(HttpStatusCode.BadRequest, server.read("mia", "/api/v1/spending?period=year").status)
        assertEquals(HttpStatusCode.Forbidden, server.read("feed", "/api/v1/spending").status)
        assertEquals(HttpStatusCode.Forbidden, server.read("bot", "/api/v1/spending").status)
        assertEquals(HttpStatusCode.Forbidden, server.read("fake", "/api/v1/spending").status)
    }

    @Test
    fun noOrganizationSeesAnotherOnesSpending() = reportsTest { server ->
        val b = server.read("carol", "/api/v1/spending").body<SpendingDto>()
        assertEquals(9.0, b.totalUsd, 1e-9)
        assertEquals(listOf(SpendingLineDto("claude-code", "claude-code", 9.0, 1)), b.byAgent)
        assertEquals(listOf("carol"), b.byPerson.map { it.label })
        assertNull(b.budgetUsd)

        assertEquals(HttpStatusCode.Unauthorized, server.read(null, "/api/v1/spending").status)
        assertEquals(HttpStatusCode.Unauthorized, server.read("forged", "/api/v1/spending").status)
        assertEquals(HttpStatusCode.NotFound, server.read("nobody", "/api/v1/spending").status)
    }

    @Test
    fun theFeedIsTheOrganizationsNewestFirstWithLoginsAndCosts() = reportsTest { server ->
        val page = server.read("mia", "/api/v1/activity").body<ActivityPageDto>()

        assertEquals(
            listOf(
                "gate.requested:$pending",
                "gate.decided:$rejected",
                "gate.decided:$approved",
                "gate.requested:$rejected",
                "workflow.changed:d1",
                "session.started:s2",
                "gate.requested:$approved",
                "session.ended:s1",
                "session.started:s1",
            ),
            page.items.map { it.id },
        )
        assertNull(page.next)
        val byId = page.items.associateBy { it.id }
        val decided = byId.getValue("gate.decided:$approved")
        assertEquals("olga", decided.actor.name)
        assertEquals("person", decided.actor.kind)
        assertEquals("olga said yes to: Merge PR #6", decided.summary)
        assertEquals(approved.toString(), decided.gateId)
        assertEquals("${CAROL.userId} said no to: Deploy", byId.getValue("gate.decided:$rejected").summary)
        assertEquals("claude asked for a yes: Publish", byId.getValue("gate.requested:$pending").summary)
        assertEquals("olga changed the workflow release", byId.getValue("workflow.changed:d1").summary)
        val ended = byId.getValue("session.ended:s1")
        assertEquals(0.5, ended.costUsd)
        assertEquals("acme/api", ended.repo)
        assertEquals("s1", ended.sessionId)
        assertEquals("agent", ended.actor.kind)
        assertEquals("mia's claude-code session ended in acme/api", ended.summary)
        assertNull(byId.getValue("session.started:s2").costUsd)

        val carol = server.read("carol", "/api/v1/activity").body<ActivityPageDto>()
        assertEquals(
            listOf("gate.decided", "gate.requested", "session.ended", "session.started", "workflow.changed").sorted(),
            carol.items.map { it.kind }.sorted(),
        )
        assertEquals(listOf("carol"), carol.items.filter { it.kind == "gate.decided" }.map { it.actor.name })
    }

    @Test
    fun aGateLineCarriesTheRepositoryItRanInAndOnlyItsOwnOrganizations() = reportsTest { server ->
        val page = server.read("mia", "/api/v1/activity").body<ActivityPageDto>()
        val repos = page.items.associate { it.id to it.repo }

        // The repository the session declared, or the one the command really ran in when the hook read another.
        assertEquals("acme/api", repos["gate.requested:$approved"])
        assertEquals("acme/api", repos["gate.decided:$approved"])
        assertEquals("acme/infra", repos["gate.requested:$rejected"])
        assertEquals("acme/infra", repos["gate.decided:$rejected"])
        // A gate outside any repository, and a workflow, carry none: the field is left out, not sent empty.
        assertNull(repos["gate.requested:$pending"])
        assertNull(repos["workflow.changed:d1"])
        val raw = Json.parseToJsonElement(server.read("mia", "/api/v1/activity").bodyAsText()).jsonObject
        val lines = raw.getValue("items").jsonArray.map { it.jsonObject }
        val publish = lines.single { it.getValue("id").jsonPrimitive.content == "gate.requested:$pending" }
        assertFalse("repo" in publish)

        // Each organization reads its own gates' repositories, never the other's.
        assertFalse("beta/secret" in repos.values)
        val carol = server.read("carol", "/api/v1/activity").body<ActivityPageDto>()
        assertEquals(
            setOf("beta/secret"),
            carol.items.filter { it.kind.startsWith("gate.") }.mapNotNull { it.repo }.toSet(),
        )
        assertTrue(carol.items.none { it.repo == "acme/infra" || it.repo == "acme/web" })
    }

    @Test
    fun theFeedPagesByItsCursorAndFiltersOnARepository() = reportsTest { server ->
        // 60 more sessions in A, one minute apart, so that the feed has two pages.
        for (index in 1..60) {
            server.session(SeedSession(
                ORG_A, "p$index", "claude-code", "acme/many", "mia", NOW - 4.hours - index.minutes,
            ))
        }
        val first = server.read("feed", "/api/v1/activity").body<ActivityPageDto>()
        assertEquals(50, first.items.size)
        val next = checkNotNull(first.next)
        val second = server.read("feed", "/api/v1/activity?before=${next.encodeURLParameter()}")
            .body<ActivityPageDto>()
        assertEquals(19, second.items.size)
        assertNull(second.next)
        assertEquals(69, (first.items + second.items).map { it.id }.toSet().size)
        assertEquals("session.started:p60", second.items.last().id)

        val api = server.read("mia", "/api/v1/activity?repo=acme%2Fapi").body<ActivityPageDto>()
        assertEquals(listOf("session.ended:s1", "session.started:s1"), api.items.map { it.id })
        val cursor = api.items.first().let { "${it.at}~${it.id}" }.encodeURLParameter()
        val olderThanEnd = server.read("mia", "/api/v1/activity?repo=acme%2Fapi&before=$cursor").body<ActivityPageDto>()
        assertEquals(listOf("session.started:s1"), olderThanEnd.items.map { it.id })

        assertEquals(HttpStatusCode.BadRequest, server.read("mia", "/api/v1/activity?before=yesterday").status)
        assertEquals(HttpStatusCode.Forbidden, server.read("spend", "/api/v1/activity").status)
        assertEquals(HttpStatusCode.Unauthorized, server.read(null, "/api/v1/activity").status)
        assertEquals(HttpStatusCode.NotFound, server.read("nobody", "/api/v1/activity").status)
    }

    @Test
    fun aBudgetPastItsLimitIsListedAsExceededToWhoeverSeesItsSpending() = reportsTest { server ->
        // mia's own budget: 0.40 $ a day, that only warns; she spent 0.50 $ today.
        server.sql(
            ORG_A,
            "INSERT INTO budgets_limits (org_id, member_id, scope, period, action, limit_micros, updated_at) " +
                "VALUES (:org, :member, 'MEMBER', 'DAY', 'WARN', 400000, :at)",
            "org" to ORG_A, "member" to MIA.memberId, "at" to NOW,
        )
        // And the organization's monthly 1 $ that stops: it spent 1.55 $ this month.
        server.koin.get<BudgetsRepository>().replaceLimits(ORG_A, BudgetTarget.Org, listOf(BudgetLimit(1_000_000)), NOW)
        // The gateway's check before her next call is what sees both.
        server.koin.get<BudgetGuard>().check(ORG_A, MIA.memberId)

        suspend fun exceeded(token: String) =
            server.read(token, "/api/v1/spending").body<JsonObject>().getValue("exceeded").jsonArray
        val (org, line) = exceeded("olga").map { it.jsonObject }
        assertEquals("organization", org.getValue("scope").jsonPrimitive.content)
        assertEquals(null, org["subject"], "The organization's own has no subject")
        assertEquals("month", org.getValue("period").jsonPrimitive.content)
        assertEquals(1.0, org.getValue("usd").jsonPrimitive.content.toDouble())
        assertEquals("true", org.getValue("hardStop").jsonPrimitive.content)
        assertEquals("member", line.getValue("scope").jsonPrimitive.content)
        assertEquals(MIA.memberId.toString(), line.getValue("subject").jsonPrimitive.content)
        assertEquals("day", line.getValue("period").jsonPrimitive.content)
        assertEquals("0.4", line.getValue("usd").jsonPrimitive.content)
        assertEquals("false", line.getValue("hardStop").jsonPrimitive.content)
        assertEquals(NOW.toString(), line.getValue("since").jsonPrimitive.content)
        assertEquals(listOf(line), exceeded("mia").map { it.jsonObject }, "mia sees her own")
        assertEquals(0, exceeded("carol").size, "Another organization sees none")

        // Raised past what she spent, the limit is no longer exceeded.
        val raise = "UPDATE budgets_limits SET limit_micros = 600000 WHERE member_id = :member"
        server.sql(ORG_A, raise, "member" to MIA.memberId)
        assertEquals(listOf(org), exceeded("olga").map { it.jsonObject })
    }
}
