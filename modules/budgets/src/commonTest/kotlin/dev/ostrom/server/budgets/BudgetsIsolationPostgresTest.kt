// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets

import dev.ostrom.server.budgets.domain.BudgetAlerts
import dev.ostrom.server.budgets.presentation.BudgetDto
import dev.ostrom.server.budgets.presentation.BudgetsPage
import dev.ostrom.server.budgets.presentation.MyBudgetResponse
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
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * `GET /api/v1/budgets/me` reads the caller's organization only: org A's limits and spend, even an override and spend
 * written in org A under org B's member id, never show in org B's member's budget.
 */
class BudgetsIsolationPostgresTest {
    private val clock = TestClock()
    private val overrides: Module = module {
        includes(TestCallers.overrides(clock))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<BudgetAlerts> { RecordingAlerts() }
    }
    private val modules = listOf(SecretsModule, UsageModule, BudgetsModule)
    private val base = "/api/v1/budgets"

    private suspend fun TestServer.putBudget(token: String, budget: BudgetDto): HttpResponse = client.put(base) {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(budget)
    }

    private suspend fun TestServer.spendInOrgA(costMicros: Long, memberId: Uuid) {
        koin.get<UsageRecorder>().record(
            UsageEvent(
                Uuid.random(),
                TestCallers.orgA,
                memberId,
                UsageKind.MODEL,
                "claude-sonnet-4-5",
                costMicros = costMicros,
                at = TestCallers.epoch,
            ),
        )
    }

    @Test
    fun anotherOrgsMemberReadsNothingOfTheBudgetsInTheirOwnBudget() = serverTest(modules, overrides) { server ->
        val memberOfB = TestCallers.adminB.memberId
        val orgLimit = BudgetDto("organization", usdPerMonth = 1.0)
        assertEquals(HttpStatusCode.OK, server.putBudget("admin-a", orgLimit).status)
        assertEquals(
            HttpStatusCode.OK,
            server.putBudget("admin-a", BudgetDto("member", memberOfB.toString(), 3.0)).status,
        )
        server.spendInOrgA(2_000_000, memberOfB)

        val mine = server.client.get("$base/me") { bearerAuth("admin-b") }
        assertEquals(HttpStatusCode.OK, mine.status)
        assertEquals(
            MyBudgetResponse(null, 0, null, "2026-11-01T00:00:00Z", exhausted = false),
            mine.body<MyBudgetResponse>(),
            "Org B's member sees no limit, no spend and no exhaustion of org A",
        )

        val budgets = server.client.get(base) { bearerAuth("admin-a") }.body<BudgetsPage>()
        assertEquals(
            listOf(BudgetDto("organization", usdPerMonth = 1.0), BudgetDto("member", memberOfB.toString(), 3.0)),
            budgets.items,
            "Org A's budgets are still there",
        )
        assertEquals(2.0, budgets.orgSpentUsd)
    }
}
