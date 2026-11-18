// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets

import run.ostrom.server.budgets.domain.BudgetDecision
import run.ostrom.server.budgets.domain.BudgetGuard
import run.ostrom.server.budgets.domain.BudgetLimit
import run.ostrom.server.budgets.domain.BudgetScope
import run.ostrom.server.budgets.domain.BudgetTarget
import run.ostrom.server.budgets.domain.BudgetWindow
import run.ostrom.server.budgets.domain.BudgetsRepository
import run.ostrom.server.budgets.presentation.MemberCapacityResponse
import run.ostrom.server.gateway.GatewayModule
import run.ostrom.server.gateway.domain.AdmissionQueueRepository
import run.ostrom.server.gateway.domain.AdmissionRequest
import run.ostrom.server.gateway.domain.Workload
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.sharing.SharingModule
import run.ostrom.server.sharing.domain.MemberPoolRepository
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.UsageModule
import run.ostrom.server.usage.domain.UsageEvent
import run.ostrom.server.usage.domain.UsageKind
import run.ostrom.server.usage.domain.UsageRecorder
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.uuid.Uuid

/**
 * TeamCode's member capacity on real PostgreSQL and HTTP, with the sharing, gateway and usage modules: the weighted
 * share of the organization's daily limit stops a member's calls, and `GET /api/v1/members/{login}/capacity` shows it
 * with their spend since midnight UTC and their calls in the admission queue, to an admin or the member, never to
 * another organization.
 */
class MemberCapacityPostgresTest {
    private val clock = TestClock()
    private val org = TestCallers.orgA
    private val member = TestCallers.memberA.memberId
    private val admin = TestCallers.adminA.memberId

    /** `member` and `admin` in org A; nobody in org B's logins. */
    private object Logins : MemberLogins {
        private val ids = mapOf("member" to TestCallers.memberA.memberId, "admin" to TestCallers.adminA.memberId)

        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> =
            if (orgId == TestCallers.orgA) ids.filterKeys { it in logins } else emptyMap()

        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> = emptyMap()
    }

    /** Org A's two members, as the orgs module lists them. */
    private object Directory : MemberDirectory {
        override suspend fun isActiveMember(orgId: Uuid, memberId: String) = true

        override suspend fun memberIds(orgId: Uuid, limit: Int): List<String> =
            listOf(TestCallers.memberA.memberId, TestCallers.adminA.memberId).map { it.toString() }
    }

    private val overrides: Module = module {
        includes(TestCallers.overrides(clock))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<MemberLogins> { Logins }
        single<MemberDirectory> { Directory }
    }
    private val modules = listOf(SecretsModule, UsageModule, SharingModule, GatewayModule, BudgetsModule)

    private suspend fun TestServer.capacity(login: String, token: String?): HttpResponse =
        client.get("/api/v1/members/$login/capacity") { token?.let { bearerAuth(it) } }

    private suspend fun TestServer.spend(memberId: Uuid, costMicros: Long) {
        koin.get<UsageRecorder>().record(
            UsageEvent(
                Uuid.random(),
                org,
                memberId,
                UsageKind.MODEL,
                "claude-sonnet-4-5",
                costMicros = costMicros,
                at = clock.now(),
            ),
        )
    }

    @Test
    fun aMembersWeightedShareOfTheOrgsDailyLimitStopsTheirCallsAndTheCapacityShowsIt() =
        serverTest(modules, overrides) { server ->
            with(server) {
                val limits = koin.get<BudgetsRepository>()
                val daily = BudgetLimit(12_000_000, BudgetWindow.DAY)
                limits.replaceLimits(org, BudgetTarget.Org, listOf(daily), clock.now())
                // The member weighs 2 and the admin 1: 8 and 4 dollars of the organization's 12.
                koin.get<MemberPoolRepository>().setPolicy(org, member, null, null, 2)
                spend(member, 3_000_000)
                spend(admin, 1_000_000)
                val queue = koin.get<AdmissionQueueRepository>()
                queue.enter(AdmissionRequest(Uuid.random(), org, member, "claude", Workload.INTERACTIVE, 1), true)
                queue.enter(AdmissionRequest(Uuid.random(), org, member, "claude", Workload.INTERACTIVE, 1), true)

                val read = capacity("member", "admin-a")
                assertEquals(HttpStatusCode.OK, read.status, read.bodyAsText())
                assertEquals("no-store", read.headers[HttpHeaders.CacheControl])
                assertEquals(
                    MemberCapacityResponse(
                        member = "member",
                        weight = 2,
                        poolTotalWeight = 3,
                        poolDailyMicros = 12_000_000,
                        shareMicros = 8_000_000,
                        dailyLimitMicros = 8_000_000,
                        spentTodayMicros = 3_000_000,
                        activeRequests = 1,
                        waitingRequests = 1,
                        resetsAt = "2026-10-31T00:00:00Z",
                    ),
                    read.body<MemberCapacityResponse>(),
                )
                assertEquals(read.bodyAsText(), capacity("member", "member-a").bodyAsText(), "The member reads it")
                assertEquals(HttpStatusCode.OK, capacity("member", "team-key-a").status, "A team:read key too")

                val guard = koin.get<BudgetGuard>()
                assertEquals(BudgetDecision.Allowed(3_000_000), guard.check(org, admin), "The admin's 4 dollars")
                spend(member, 5_000_000)
                val stopped = assertIs<BudgetDecision.Exhausted>(guard.check(org, member))
                assertEquals(BudgetScope.MEMBER to 8_000_000L, stopped.scope to stopped.limitMicros)
                assertIs<BudgetDecision.Allowed>(guard.check(org, admin), "The admin's share is theirs")
            }
        }

    @Test
    fun onlyAnAdminOrTheMemberReadsItAndNeverAnotherOrganization() = serverTest(modules, overrides) { server ->
        with(server) {
            assertEquals(HttpStatusCode.Unauthorized, capacity("member", null).status)
            assertEquals(HttpStatusCode.Forbidden, capacity("admin", "member-a").status, "Another member's")
            assertEquals(HttpStatusCode.Forbidden, capacity("ghost", "member-a").status, "No login enumeration")
            assertEquals(HttpStatusCode.NotFound, capacity("ghost", "admin-a").status)
            assertEquals(HttpStatusCode.NotFound, capacity("member", "admin-b").status, "Org B knows no such login")
            assertEquals(HttpStatusCode.Forbidden, capacity("member", "cli-other").status, "No budgets:read")
            val own = capacity("member", "member-a").body<MemberCapacityResponse>()
            assertEquals(MemberCapacityResponse("member", null, 1, 2, null, null, null, 0, 0, 0, own.resetsAt), own)
        }
    }
}
