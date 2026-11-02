// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets

import dev.ostrom.server.budgets.domain.BudgetAlert
import dev.ostrom.server.budgets.domain.BudgetAlerts
import dev.ostrom.server.budgets.domain.BudgetDecision
import dev.ostrom.server.budgets.domain.BudgetGuard
import dev.ostrom.server.budgets.domain.BudgetScope
import dev.ostrom.server.budgets.domain.BudgetsRepository
import dev.ostrom.server.budgets.domain.SharedLimit
import dev.ostrom.server.budgets.presentation.BudgetDto
import dev.ostrom.server.budgets.presentation.BudgetsPage
import dev.ostrom.server.budgets.presentation.MyBudgetResponse
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.secrets.infrastructure.executeInOrg
import dev.ostrom.server.secrets.infrastructure.fetchInOrg
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import dev.ostrom.server.usage.UsageModule
import dev.ostrom.server.usage.domain.UsageEvent
import dev.ostrom.server.usage.domain.UsageKind
import dev.ostrom.server.usage.domain.UsageRecorder
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Real PostgreSQL and real HTTP: the limits, the guard on the real ledger, alerts once, roles and org isolation. */
class BudgetsPostgresTest {
    private val clock = TestClock()
    private val alerts = RecordingAlerts()
    private val overrides: Module = module {
        includes(TestCallers.overrides(clock))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<BudgetAlerts> { alerts }
    }
    private val modules = listOf(SecretsModule, UsageModule, BudgetsModule)

    // The org comes from the token: admin-a and member-a are in org A, admin-b in org B.
    private val base = "/api/v1/budgets"
    private val member = TestCallers.memberA.memberId

    private suspend fun TestServer.putBudget(token: String, budget: BudgetDto): HttpResponse = client.put(base) {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(budget)
    }

    private suspend fun TestServer.budgets(token: String = "admin-a") =
        client.get(base) { bearerAuth(token) }.body<BudgetsPage>()

    private suspend fun TestServer.spend(
        costMicros: Long,
        memberId: Uuid?,
        at: Instant = TestCallers.epoch,
        orgId: Uuid = TestCallers.orgA,
    ) {
        koin.get<UsageRecorder>().record(
            UsageEvent(
                Uuid.random(),
                orgId,
                memberId,
                UsageKind.MODEL,
                "claude-sonnet-4-5",
                costMicros = costMicros,
                at = at,
            ),
        )
    }

    @Test
    fun withoutAnAlertsModuleTheGuardStillDecidesOnAMemberOverride() = serverTest(
        modules,
        module {
            includes(TestCallers.overrides(clock))
            single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        },
    ) { server ->
        val repository = server.koin.get<BudgetsRepository>()
        repository.putSharedLimit(TestCallers.orgA, SharedLimit.DEFAULT_MEMBER, 500_000, TestCallers.epoch)
        repository.putMemberLimit(TestCallers.orgA, member, 2_000_000, TestCallers.epoch)
        assertEquals(2_000_000, repository.limits(TestCallers.orgA, member).memberLimitMicros, "The override wins")
        server.spend(1_900_000, member)
        assertEquals(
            BudgetDecision.Allowed(100_000),
            server.koin.get<BudgetGuard>().check(TestCallers.orgA, member),
        )
    }

    @Test
    fun anAdminManagesTheLimitsAndTheOverrides() = serverTest(modules, overrides) { server ->
        val org = BudgetDto("organization", usdPerMonth = 100.0)
        val saved = server.putBudget("admin-a", org)
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        assertEquals(org, saved.body<BudgetDto>())
        assertEquals(
            BudgetDto("member", usdPerMonth = 20.0),
            server.putBudget("admin-a", BudgetDto("MEMBER", usdPerMonth = 20.0)).body<BudgetDto>(),
            "The scope is answered as the API lists it",
        )
        server.putBudget("admin-a", BudgetDto("member", member.toString(), 50.0))
        server.putBudget("admin-a", BudgetDto("member", member.toString(), 40.0))
        server.spend(3_000_000, member)
        server.spend(9_000_000, member, at = TestCallers.monthStart - 1.days)

        val budgets = server.budgets()
        assertEquals(
            listOf(
                BudgetDto("organization", usdPerMonth = 100.0),
                BudgetDto("member", usdPerMonth = 20.0),
                BudgetDto("member", member.toString(), 40.0),
            ),
            budgets.items,
            "An override is replaced",
        )
        assertEquals(3.0, budgets.orgSpentUsd, "Only this month counts")
        assertEquals("2026-10-01T00:00:00Z", budgets.periodStart)
        assertEquals("2026-11-01T00:00:00Z", budgets.resetsAt)

        repeat(2) {
            val removed = server.putBudget("admin-a", BudgetDto("member", member.toString()))
            assertEquals(HttpStatusCode.OK, removed.status, "Removing an absent override is a no-op")
        }
        server.putBudget("admin-a", BudgetDto("org"))
        assertEquals(listOf(BudgetDto("member", usdPerMonth = 20.0)), server.budgets().items)
        server.putBudget("admin-a", BudgetDto("member"))
        assertEquals(emptyList(), server.budgets().items)
    }

    @Test
    fun aMemberSeesTheirOwnBudgetButCannotChangeAny() = serverTest(modules, overrides) { server ->
        server.putBudget("admin-a", BudgetDto("member", usdPerMonth = 1.0))
        server.spend(400_000, member)
        server.spend(900_000, TestCallers.adminA.memberId)

        val mine = server.client.get("$base/me") { bearerAuth("member-a") }.body<MyBudgetResponse>()
        assertEquals(MyBudgetResponse(1_000_000, 400_000, 600_000, "2026-11-01T00:00:00Z", exhausted = false), mine)

        assertEquals(HttpStatusCode.Forbidden, server.client.get(base) { bearerAuth("member-a") }.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            server.putBudget("member-a", BudgetDto("organization", null, 1.0)).status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            server.putBudget("member-a", BudgetDto("member", member.toString(), 9.0)).status,
        )
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("$base/me").status)
        val refused = listOf(
            BudgetDto("organization", usdPerMonth = -1.0),
            BudgetDto("organization", usdPerMonth = 1.0, usdPerDay = 1.0),
            BudgetDto("organization", usdPerMonth = 1.0, hardStop = false),
            BudgetDto("workflow", "deploy", 1.0),
            BudgetDto("member", "not-a-uuid", 1.0),
        )
        for (budget in refused) {
            assertEquals(HttpStatusCode.BadRequest, server.putBudget("admin-a", budget).status, budget.toString())
        }
        assertEquals(
            listOf(BudgetDto("member", usdPerMonth = 1.0)),
            server.budgets().items,
            "A refused budget changes nothing",
        )
    }

    @Test
    fun eachRouteChecksTheCredentialAndItsScope() = serverTest(modules, overrides) { server ->
        val budget = BudgetDto("organization", usdPerMonth = 5.0)
        assertEquals(HttpStatusCode.Unauthorized, server.client.get(base).status)
        for (token in listOf("stranger")) {
            assertEquals(HttpStatusCode.NotFound, server.client.get(base) { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.NotFound, server.putBudget(token, budget).status)
            assertEquals(HttpStatusCode.NotFound, server.client.get("$base/me") { bearerAuth(token) }.status)
        }
        for (token in listOf("device-a", "agent-a", "cli-write", "cli-other")) {
            assertEquals(HttpStatusCode.Forbidden, server.client.get(base) { bearerAuth(token) }.status, token)
        }
        for (token in listOf("device-a", "agent-a", "cli-read", "cli-other")) {
            assertEquals(HttpStatusCode.Forbidden, server.putBudget(token, budget).status, token)
        }
        for (token in listOf("device-a", "cli-write", "cli-other")) {
            assertEquals(HttpStatusCode.Forbidden, server.client.get("$base/me") { bearerAuth(token) }.status, token)
        }
        // S2-52: a personal token holding budgets:write is still not a console session.
        assertEquals(HttpStatusCode.Forbidden, server.putBudget("cli-write", budget).status)
        assertEquals(HttpStatusCode.OK, server.putBudget("admin-a", budget).status)
        assertEquals(listOf(budget), server.budgets("cli-read").items)
        assertEquals(HttpStatusCode.OK, server.client.get("$base/me") { bearerAuth("agent-a") }.status)
        assertEquals(HttpStatusCode.OK, server.client.get("$base/me") { bearerAuth("cli-read") }.status)
    }

    @Test
    fun theGuardStopsSpendingAtTheLimitAndAlertsOnce() = serverTest(modules, overrides) { server ->
        server.putBudget("admin-a", BudgetDto("organization", usdPerMonth = 1.0))
        val guard = server.koin.get<BudgetGuard>()
        assertEquals(BudgetDecision.Allowed(1_000_000), guard.check(TestCallers.orgA, member))

        server.spend(850_000, member)
        clock.advance(5.seconds)
        assertEquals(BudgetDecision.Allowed(150_000), guard.check(TestCallers.orgA, member))
        assertEquals(listOf(80), alerts.alerts.map { it.thresholdPercent })

        server.spend(150_000, TestCallers.adminA.memberId)
        clock.advance(5.seconds)
        val exhausted = BudgetDecision.Exhausted(BudgetScope.ORG, 1_000_000, TestCallers.nextMonth)
        assertEquals(exhausted, guard.check(TestCallers.orgA, member))
        assertEquals(exhausted, guard.check(TestCallers.orgA, TestCallers.adminA.memberId))
        assertEquals(
            listOf(80, 100),
            alerts.alerts.map { it.thresholdPercent },
            "Each threshold once, for the whole org",
        )

        val mine = server.client.get("$base/me") { bearerAuth("member-a") }.body<MyBudgetResponse>()
        assertEquals(true, mine.exhausted)
        assertEquals("org", mine.exhaustedScope)

        server.putBudget("admin-a", BudgetDto("organization", usdPerMonth = 2.0))
        assertEquals(
            BudgetDecision.Allowed(1_000_000),
            guard.check(TestCallers.orgA, member),
            "A raised limit applies at once",
        )
    }

    @Test
    fun anAlertIsMarkedOnceInTheDatabase() = serverTest(modules, overrides) { server ->
        val repository = server.koin.get<BudgetsRepository>()
        val alert = BudgetAlert(TestCallers.orgA, null, BudgetScope.ORG, 80, 1_000, 900, TestCallers.monthStart)
        val memberAlert = alert.copy(memberId = member, scope = BudgetScope.MEMBER)

        assertEquals(true, repository.markAlerted(alert, TestCallers.epoch))
        assertEquals(false, repository.markAlerted(alert, TestCallers.epoch), "Another replica sees it was sent")
        assertEquals(true, repository.markAlerted(memberAlert, TestCallers.epoch))
        assertEquals(false, repository.markAlerted(memberAlert, TestCallers.epoch))
        assertEquals(true, repository.markAlerted(alert.copy(thresholdPercent = 100), TestCallers.epoch))
        assertEquals(true, repository.markAlerted(alert.copy(limitMicros = 2_000), TestCallers.epoch), "A new limit")

        repository.unmarkAlerted(alert)
        repository.unmarkAlerted(memberAlert)
        assertEquals(true, repository.markAlerted(alert, TestCallers.epoch), "A failed alert is sent again")
        assertEquals(true, repository.markAlerted(memberAlert, TestCallers.epoch))
    }

    @Test
    fun oneOrgWideLimitIsWrittenWithoutTheOther() = serverTest(modules, overrides) { server ->
        val repository = server.koin.get<BudgetsRepository>()
        repository.putOrgLimits(TestCallers.orgA, 5_000, 1_000, TestCallers.epoch)

        repository.putSharedLimit(TestCallers.orgA, SharedLimit.ORG, 7_000, TestCallers.epoch)
        repository.putSharedLimit(TestCallers.orgA, SharedLimit.DEFAULT_MEMBER, null, TestCallers.epoch)

        val settings = repository.settings(TestCallers.orgA, maxOverrides = 0)
        assertEquals(7_000L to null, settings.orgLimitMicros to settings.defaultMemberLimitMicros)
    }

    @Test
    fun theOrgLimitsSurviveManyOverrides() = serverTest(modules, overrides) { server ->
        val repository = server.koin.get<BudgetsRepository>()
        repository.putOrgLimits(TestCallers.orgA, 5_000, 1_000, TestCallers.epoch)
        repeat(3) { repository.putMemberLimit(TestCallers.orgA, Uuid.random(), 10, TestCallers.epoch) }

        val settings = repository.settings(TestCallers.orgA, maxOverrides = 2)
        assertEquals(5_000L, settings.orgLimitMicros, "The org-wide rows are read before the overrides")
        assertEquals(1_000L, settings.defaultMemberLimitMicros)
        assertEquals(2, settings.overrides.size)
    }

    @Test
    fun anotherOrgNeitherSeesNorChangesTheBudgets() = serverTest(modules, overrides) { server ->
        server.putBudget("admin-a", BudgetDto("organization", usdPerMonth = 7.0))
        server.putBudget("admin-a", BudgetDto("member", member.toString(), 3.0))

        val own = server.budgets("admin-b")
        assertEquals(emptyList(), own.items, "Org B's admin reads org B, which has no budget")
        assertEquals(
            HttpStatusCode.OK,
            server.putBudget("admin-b", BudgetDto("member", member.toString())).status,
            "Removing org A's override from org B is a no-op",
        )
        server.putBudget("admin-b", BudgetDto("organization", usdPerMonth = 1.0))
        server.spend(5_000_000, TestCallers.adminB.memberId, orgId = TestCallers.orgB)
        assertEquals(listOf(BudgetDto("organization", usdPerMonth = 1.0)), server.budgets("admin-b").items)

        val budgets = server.budgets()
        assertEquals(
            listOf(BudgetDto("organization", usdPerMonth = 7.0), BudgetDto("member", member.toString(), 3.0)),
            budgets.items,
            "Unchanged by org B",
        )
        assertEquals(0.0, budgets.orgSpentUsd, "Org B's spend never counts for org A")
    }

    @Test
    fun rowLevelSecurityConfinesEveryQueryToItsOrg() = serverTest(modules, overrides) { server ->
        val repository = server.koin.get<BudgetsRepository>()
        repository.putOrgLimits(TestCallers.orgA, 5_000, 1_000, TestCallers.epoch)
        repository.putMemberLimit(TestCallers.orgA, member, 10, TestCallers.epoch)
        val alert = BudgetAlert(TestCallers.orgA, null, BudgetScope.ORG, 80, 1_000, 900, TestCallers.monthStart)
        repository.markAlerted(alert, TestCallers.epoch)
        for (table in listOf("budgets_limits", "budgets_alerts")) {
            val count = Statement.create("SELECT COUNT(*) AS n FROM $table")
            suspend fun visibleTo(org: Uuid) =
                server.driver.fetchInOrg(org, count).getOrThrow().first().get("n").asLong()
            assertEquals(0L, visibleTo(TestCallers.orgB), "$table: a query without an org filter sees only its org")
            assertEquals(
                0L,
                server.driver.executeInOrg(TestCallers.orgB, Statement.create("DELETE FROM $table")).getOrThrow(),
                "$table: nor deleted by another org",
            )
            assertTrue(visibleTo(TestCallers.orgA) > 0, table)
        }
        val foreign = Statement.create(
            "INSERT INTO budgets_limits (org_id, member_id, scope, limit_micros, updated_at) " +
                "VALUES (:org, :member, 'MEMBER', 1, now())",
        ).bind("org", TestCallers.orgA).bind("member", Uuid.random())
        assertFails("A row of another org is refused") {
            server.driver.executeInOrg(TestCallers.orgB, foreign).getOrThrow()
        }
    }
}
