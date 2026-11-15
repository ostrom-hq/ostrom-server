// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.budgets

import run.ostrom.server.budgets.domain.BudgetDecision
import run.ostrom.server.budgets.domain.BudgetGuard
import run.ostrom.server.budgets.domain.BudgetLimit
import run.ostrom.server.budgets.domain.BudgetTarget
import run.ostrom.server.budgets.domain.BudgetWindow
import run.ostrom.server.budgets.domain.BudgetsRepository
import run.ostrom.server.budgets.presentation.BudgetOverrideDto
import run.ostrom.server.budgets.presentation.BudgetOverridesPage
import run.ostrom.server.budgets.presentation.GrantBudgetOverrideRequest
import run.ostrom.server.budgets.presentation.MyBudgetResponse
import run.ostrom.server.budgets.presentation.RevokeBudgetOverrideRequest
import run.ostrom.server.gateway.domain.SpendDecision
import run.ostrom.server.gateway.domain.SpendGuard
import run.ostrom.server.gateway.domain.SpendRequest
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.UsageModule
import run.ostrom.server.usage.domain.UsageEvent
import run.ostrom.server.usage.domain.UsageKind
import run.ostrom.server.usage.domain.UsageRecorder
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * Members' temporary daily budgets on real PostgreSQL and HTTP: who manages them, the stale replacement, the kept
 * revocation, the history's pages, the guard and the ledger's admission reading the override, its end by the
 * database's clock with no job, the audit, and the org's walls.
 */
class BudgetOverridesPostgresTest {
    private val clock = TestClock()
    private val audit = mutableListOf<OrgAuditRecord>()
    private val member = TestCallers.memberA.memberId
    private val overrides: Module = module {
        includes(TestCallers.overrides(clock))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<OrgAuditTrail> { OrgAuditTrail { audit += it } }
        single<MemberLogins> { Logins }
    }
    private val modules = listOf(SecretsModule, UsageModule, BudgetsModule)
    private val path = "/api/v1/members/member/budget-overrides"

    private suspend fun TestServer.postJson(path: String, token: String, body: Any): HttpResponse =
        client.post(path) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun TestServer.grant(
        token: String = "admin-a",
        micros: Long = 300_000,
        replacesId: String? = null,
    ): HttpResponse = postJson(path, token, GrantBudgetOverrideRequest(micros, 60, "Support incident", replacesId))

    private suspend fun TestServer.history(token: String = "admin-a", query: String = ""): HttpResponse =
        client.get(path + query) { bearerAuth(token) }

    private suspend fun errorType(response: HttpResponse): String =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("type")
            .jsonPrimitive.content

    @Test
    fun onlyAnAdministratorSignedInManagesThem() = serverTest(modules, overrides) { server ->
        // Only an owner or admin, a person: a member, a key even with the scopes, an agent and a device are refused.
        for (token in listOf("member-a", "key-a", "agent-a", "device-a")) {
            assertEquals(HttpStatusCode.Forbidden, server.history(token).status, token)
            assertEquals(HttpStatusCode.Forbidden, server.grant(token).status, token)
        }
        assertEquals(HttpStatusCode.Unauthorized, server.client.get(path).status)
        assertEquals(HttpStatusCode.NotFound, server.history("stranger").status, "A token of no member of an org")
        assertEquals(HttpStatusCode.OK, server.history("cli-read").status, "A person's token with budgets:read")
        val stale = server.grant("stale-admin-a")
        assertEquals(HttpStatusCode.Forbidden, stale.status)
        assertEquals("step_up_required", errorType(stale))
        val unknown = server.postJson("$path/${Uuid.random()}/revoke", "admin-a", RevokeBudgetOverrideRequest("x"))
        assertEquals(HttpStatusCode.NotFound, unknown.status)
    }

    @Test
    fun anAdministratorGrantsReplacesAndRevokesAndTheGuardFollows() = serverTest(modules, overrides) { server ->
        // The day is the database's: the spend counts from today, whatever the test clock said.
        clock.now = Clock.System.now()
        val repository = server.koin.get<BudgetsRepository>()
        val dayLimit = listOf(BudgetLimit(100_000, BudgetWindow.DAY))
        repository.replaceLimits(TestCallers.orgA, BudgetTarget.Member(member), dayLimit, TestCallers.epoch)
        server.spend(80_000)
        val granted = server.grant()
        assertEquals(HttpStatusCode.Created, granted.status, granted.bodyAsText())
        val first = granted.body<BudgetOverrideDto>()
        assertEquals("member", first.member)
        assertEquals("admin", first.createdBy)
        assertEquals(300_000, first.micros)
        assertEquals(listOf(first), server.history().body<BudgetOverridesPage>().items)
        val otherOrg = server.client.get(path) { bearerAuth("admin-b") }
        assertEquals(HttpStatusCode.NotFound, otherOrg.status, "Org B has no member of that login")

        val guard = server.koin.get<BudgetGuard>()
        val refusedOrder = server.grant(micros = 400_000)
        assertEquals(HttpStatusCode.Conflict, refusedOrder.status, "replacesId must name the override in force")
        assertEquals("stale_budget_override", errorType(refusedOrder))
        assertEquals(BudgetDecision.Allowed(220_000), guard.check(TestCallers.orgA, member), "A total, not more")
        val spendGuard = server.koin.get<SpendGuard>()
        val call = SpendRequest(TestCallers.orgA, member, Uuid.random(), 200_000)
        assertEquals(SpendDecision.Allowed, spendGuard.check(call), "The ledger's admission reads it too")
        spendGuard.release(TestCallers.orgA, call.callId)

        val mine = server.client.get("/api/v1/budgets/me") { bearerAuth("member-a") }.body<MyBudgetResponse>()
        assertEquals(first.id, mine.override?.id)
        assertEquals(100_000, mine.baseLimitMicros)

        val replaced = server.grant(micros = 0, replacesId = first.id)
        assertEquals(HttpStatusCode.Created, replaced.status, replaced.bodyAsText())
        val second = replaced.body<BudgetOverrideDto>()
        assertEquals(first.id, second.replacesId)
        assertIs<BudgetDecision.Exhausted>(guard.check(TestCallers.orgA, member), "Zero stops the calls")
        val page = server.history().body<BudgetOverridesPage>()
        assertEquals(listOf(second.id, first.id), page.items.map { it.id })
        assertEquals(second.id, page.activeId)
        assertEquals("admin", page.items[1].revokedBy)
        assertNotNull(page.items[1].revokedAt)

        val revokePath = "$path/${second.id}/revoke"
        val revoked = server.postJson(revokePath, "admin-a", RevokeBudgetOverrideRequest(" Resolved "))
        assertEquals(HttpStatusCode.OK, revoked.status, revoked.bodyAsText())
        val kept = revoked.body<BudgetOverrideDto>()
        assertEquals("Resolved", kept.revocationReason)
        val again = server.postJson(revokePath, "admin-a", RevokeBudgetOverrideRequest("Other"))
        assertEquals(kept, again.body<BudgetOverrideDto>(), "The first revocation is kept")
        assertEquals(BudgetDecision.Allowed(20_000), guard.check(TestCallers.orgA, member), "Back to the member's")
        assertNull(server.history().body<BudgetOverridesPage>().activeId)
        assertEquals(
            listOf(
                "budget_override.granted" to "member/${first.id}",
                "budget_override.superseded" to "member/${first.id}",
                "budget_override.granted" to "member/${second.id}",
                "budget_override.revoked" to "member/${second.id}",
            ),
            audit.map { it.event to it.subjectId },
        )
    }

    @Test
    fun anOverrideEndsByTheDatabasesClockWithNoJob() = serverTest(modules, overrides) { server ->
        val granted = server.grant().body<BudgetOverrideDto>()
        assertNotNull(server.history().body<BudgetOverridesPage>().activeId)
        server.database.superuser.execute(
            "UPDATE budgets_overrides SET expires_at = now() - interval '1 second'",
        ).getOrThrow()
        assertNull(server.history().body<BudgetOverridesPage>().activeId)
        val mine = server.client.get("/api/v1/budgets/me") { bearerAuth("member-a") }.body<MyBudgetResponse>()
        assertNull(mine.override)
        val ended = server.postJson("$path/${granted.id}/revoke", "admin-a", RevokeBudgetOverrideRequest("Late"))
        assertEquals(HttpStatusCode.Conflict, ended.status)
        assertEquals("stale_budget_override", errorType(ended))
        val stale = server.grant(replacesId = granted.id)
        assertEquals(HttpStatusCode.Conflict, stale.status, "An ended override authorizes no replacement")
        assertEquals(HttpStatusCode.Conflict, server.grant(replacesId = "not-an-id").status)
        assertEquals(HttpStatusCode.Created, server.grant().status)
    }

    @Test
    fun theHistoryPagesByFiftyAndRefusesWhatItCannotTake() = serverTest(modules, overrides) { server ->
        server.database.superuser.execute(
            "INSERT INTO budgets_overrides (id, org_id, member_id, micros, created_by, reason, created_at, " +
                "expires_at, revoked_at) SELECT gen_random_uuid(), '${TestCallers.orgA}', '$member', i, " +
                "gen_random_uuid(), 'old', now() - make_interval(0, 0, 0, 0, 0, i), now(), now() " +
                "FROM generate_series(1, 55) AS i",
        ).getOrThrow()
        val first = server.history().body<BudgetOverridesPage>()
        assertEquals(50, first.items.size)
        assertEquals((1L..50L).toList(), first.items.map { it.micros }, "Newest first")
        assertEquals(36, first.items.first().createdBy.length, "A grantor who left is named by id")
        val next = assertNotNull(first.next)
        val second = server.history(query = "?before=$next").body<BudgetOverridesPage>()
        assertEquals((51L..55L).toList(), second.items.map { it.micros })
        assertNull(second.next)
        assertEquals(HttpStatusCode.NotFound, server.history(query = "?before=${Uuid.random()}").status)
        assertEquals(HttpStatusCode.NotFound, server.history(query = "?before=nope").status)

        val tooMuch = server.grant(micros = -1)
        assertEquals(HttpStatusCode.BadRequest, tooMuch.status)
        assertEquals("invalid_budget_override", errorType(tooMuch))
        val unknown = server.client.get("/api/v1/members/nobody/budget-overrides") { bearerAuth("admin-a") }
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        // Org B sees none of org A's overrides, even for a login it knows.
        val other = server.client.get("/api/v1/members/member-b/budget-overrides") { bearerAuth("admin-b") }
        assertEquals(HttpStatusCode.OK, other.status)
        assertTrue(other.body<BudgetOverridesPage>().items.isEmpty())
    }

    @Test
    fun aMemberOrAnOrganizationPastItsBoundsIsRefusedNewGrants() = serverTest(modules, overrides) { server ->
        val fill = { count: Int, memberOf: String ->
            "INSERT INTO budgets_overrides (id, org_id, member_id, micros, created_by, reason, created_at, " +
                "expires_at, revoked_at) SELECT gen_random_uuid(), '${TestCallers.orgA}', $memberOf, 0, " +
                "gen_random_uuid(), 'old', now(), now(), now() FROM generate_series(1, $count) AS i"
        }
        server.database.superuser.execute(fill(4999, "'$member'")).getOrThrow()
        val last = server.grant()
        assertEquals(HttpStatusCode.Created, last.status, "The 5 000th")
        val full = server.grant(replacesId = last.body<BudgetOverrideDto>().id)
        assertEquals(HttpStatusCode.BadRequest, full.status)
        assertEquals("invalid_budget_override", errorType(full))
        server.database.superuser.execute("DELETE FROM budgets_overrides").getOrThrow()
        server.database.superuser.execute(fill(100_000, "gen_random_uuid()")).getOrThrow()
        assertEquals(HttpStatusCode.BadRequest, server.grant().status, "The organization's 100 000")
    }

    private suspend fun TestServer.spend(costMicros: Long) {
        koin.get<UsageRecorder>().record(
            UsageEvent(
                Uuid.random(),
                TestCallers.orgA,
                member,
                UsageKind.MODEL,
                "claude-sonnet-4-5",
                costMicros = costMicros,
                at = clock.now(),
            ),
        )
    }

    /** `member` and `admin` in org A; `member-b` in org B, under org A's member id to try the wall. */
    private object Logins : MemberLogins {
        private val byOrg = mapOf(
            TestCallers.orgA to mapOf("member" to TestCallers.memberA.memberId, "admin" to TestCallers.adminA.memberId),
            TestCallers.orgB to mapOf("member-b" to TestCallers.memberA.memberId),
        )

        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> =
            byOrg[orgId].orEmpty().filterKeys { it in logins }

        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
            byOrg[orgId].orEmpty().entries.filter { it.value in memberIds }.associate { it.value to it.key }
    }
}
