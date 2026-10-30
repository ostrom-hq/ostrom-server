// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets

import dev.ostrom.server.budgets.domain.BudgetAlert
import dev.ostrom.server.budgets.domain.BudgetAlerts
import dev.ostrom.server.budgets.domain.BudgetDecision
import dev.ostrom.server.budgets.domain.BudgetGuard
import dev.ostrom.server.budgets.domain.BudgetScope
import dev.ostrom.server.budgets.domain.BudgetsRepository
import dev.ostrom.server.budgets.presentation.BudgetsResponse
import dev.ostrom.server.budgets.presentation.MemberBudgetResponse
import dev.ostrom.server.budgets.presentation.MyBudgetResponse
import dev.ostrom.server.budgets.presentation.PutBudgetsRequest
import dev.ostrom.server.budgets.presentation.PutMemberBudgetRequest
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import dev.ostrom.server.usage.UsageModule
import dev.ostrom.server.usage.domain.UsageEvent
import dev.ostrom.server.usage.domain.UsageKind
import dev.ostrom.server.usage.domain.UsageRecorder
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
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
    private val base = "/v1/orgs/${TestCallers.orgA}/budgets"
    private val member = TestCallers.memberA.memberId

    private suspend fun TestServer.putBudgets(
        token: String,
        request: PutBudgetsRequest,
        path: String = base,
    ): HttpResponse =
        client.put(path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun TestServer.putMember(
        token: String,
        memberId: String,
        limit: Long,
        path: String = base,
    ): HttpResponse =
        client.put("$path/members/$memberId") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PutMemberBudgetRequest(limit))
        }

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
    fun anAdminManagesTheLimitsAndTheOverrides() = serverTest(modules, overrides) { server ->
        assertEquals(HttpStatusCode.OK, server.putBudgets("admin-a", PutBudgetsRequest(100_000_000, 20_000_000)).status)
        val created = server.putMember("admin-a", member.toString(), 50_000_000)
        assertEquals(HttpStatusCode.OK, created.status, created.bodyAsText())
        assertEquals(50_000_000L, created.body<MemberBudgetResponse>().limitMicros)
        assertEquals(
            HttpStatusCode.OK,
            server.putMember("admin-a", member.toString(), 40_000_000).status,
            "An override is replaced",
        )
        server.spend(3_000_000, member)
        server.spend(9_000_000, member, at = TestCallers.monthStart - 1.days)

        val budgets = server.client.get(base) { bearerAuth("admin-a") }.body<BudgetsResponse>()
        assertEquals(100_000_000L, budgets.orgLimitMicros)
        assertEquals(20_000_000L, budgets.defaultMemberLimitMicros)
        assertEquals(listOf(member.toString() to 40_000_000L), budgets.overrides.map { it.memberId to it.limitMicros })
        assertEquals(3_000_000L, budgets.orgSpentMicros, "Only this month counts")
        assertEquals("2026-11-01T00:00:00Z", budgets.resetsAt)

        assertEquals(
            HttpStatusCode.NoContent,
            server.client.delete("$base/members/$member") { bearerAuth("admin-a") }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.delete("$base/members/$member") { bearerAuth("admin-a") }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.delete("$base/members/not-a-uuid") { bearerAuth("admin-a") }.status,
        )
        assertEquals(emptyList(), server.client.get(base) { bearerAuth("admin-a") }.body<BudgetsResponse>().overrides)

        server.putBudgets("admin-a", PutBudgetsRequest())
        val cleared = server.client.get(base) { bearerAuth("admin-a") }.body<BudgetsResponse>()
        assertEquals(null, cleared.orgLimitMicros)
        assertEquals(null, cleared.defaultMemberLimitMicros)
    }

    @Test
    fun aMemberSeesTheirOwnBudgetButCannotChangeAny() = serverTest(modules, overrides) { server ->
        server.putBudgets("admin-a", PutBudgetsRequest(defaultMemberLimitMicros = 1_000_000))
        server.spend(400_000, member)
        server.spend(900_000, TestCallers.adminA.memberId)

        val mine = server.client.get("$base/me") { bearerAuth("member-a") }.body<MyBudgetResponse>()
        assertEquals(MyBudgetResponse(1_000_000, 400_000, 600_000, "2026-11-01T00:00:00Z", exhausted = false), mine)

        assertEquals(HttpStatusCode.Forbidden, server.client.get(base) { bearerAuth("member-a") }.status)
        assertEquals(HttpStatusCode.Forbidden, server.putBudgets("member-a", PutBudgetsRequest(1, 1)).status)
        assertEquals(HttpStatusCode.Forbidden, server.putMember("member-a", member.toString(), 9_000_000).status)
        assertEquals(
            HttpStatusCode.Forbidden,
            server.client.delete("$base/members/$member") { bearerAuth("member-a") }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("$base/me").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            server.putBudgets("admin-a", PutBudgetsRequest(orgLimitMicros = -1)).status,
        )
        assertEquals(HttpStatusCode.NotFound, server.putMember("admin-a", "not-a-uuid", 1).status)
    }

    @Test
    fun theGuardStopsSpendingAtTheLimitAndAlertsOnce() = serverTest(modules, overrides) { server ->
        server.putBudgets("admin-a", PutBudgetsRequest(orgLimitMicros = 1_000_000))
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

        server.putBudgets("admin-a", PutBudgetsRequest(orgLimitMicros = 2_000_000))
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
        server.putBudgets("admin-a", PutBudgetsRequest(orgLimitMicros = 7_000_000))
        server.putMember("admin-a", member.toString(), 3_000_000)

        assertEquals(HttpStatusCode.NotFound, server.client.get(base) { bearerAuth("admin-b") }.status)
        assertEquals(
            HttpStatusCode.NotFound,
            server.putBudgets("admin-b", PutBudgetsRequest(orgLimitMicros = 1)).status,
        )
        assertEquals(HttpStatusCode.NotFound, server.putMember("admin-b", member.toString(), 1).status)
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.delete("$base/members/$member") { bearerAuth("admin-b") }.status,
        )
        assertEquals(HttpStatusCode.NotFound, server.client.get("$base/me") { bearerAuth("admin-b") }.status)

        val orgB = "/v1/orgs/${TestCallers.orgB}/budgets"
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.delete("$orgB/members/$member") { bearerAuth("admin-b") }.status,
            "Org A's override is not org B's",
        )
        val own = server.client.get(orgB) { bearerAuth("admin-b") }.body<BudgetsResponse>()
        assertEquals(null, own.orgLimitMicros)
        assertEquals(emptyList(), own.overrides)
        server.spend(5_000_000, TestCallers.adminB.memberId, orgId = TestCallers.orgB)

        val budgets = server.client.get(base) { bearerAuth("admin-a") }.body<BudgetsResponse>()
        assertEquals(7_000_000L, budgets.orgLimitMicros, "Unchanged by org B")
        assertEquals(0L, budgets.orgSpentMicros, "Org B's spend never counts for org A")
        assertEquals(listOf(3_000_000L), budgets.overrides.map { it.limitMicros })
    }
}
