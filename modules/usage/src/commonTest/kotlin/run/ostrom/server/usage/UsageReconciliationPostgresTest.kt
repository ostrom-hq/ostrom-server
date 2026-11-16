// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage

import run.ostrom.server.kernel.database.databaseNowPlus
import run.ostrom.server.usage.infrastructure.UsageHolds
import run.ostrom.server.usage.infrastructure.UsageReconciliations
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.time.Duration.Companion.hours
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.domain.Admission
import run.ostrom.server.usage.domain.HoldWindow
import run.ostrom.server.usage.domain.SpendHold
import run.ostrom.server.usage.domain.SpendLimits
import run.ostrom.server.usage.domain.SpendScope
import run.ostrom.server.usage.domain.UsageRepository
import run.ostrom.server.usage.presentation.PendingUsagePage
import run.ostrom.server.usage.presentation.ReconcileUsageRequest
import run.ostrom.server.usage.presentation.UsageReconciliationDto
import run.ostrom.server.usage.presentation.UsageReconciliationsPage
import run.ostrom.server.usage.presentation.UsageReviewDto
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * The usage review on real PostgreSQL and HTTP: who reviews, what is pending (an expired hold no correction resolved,
 * by the database's clock), the correction rewriting the hold's charge where it counts, its retry and its conflicts,
 * the pages, the retention, the audit, and the org's walls.
 */
class UsageReconciliationPostgresTest {
    private val clock = TestClock()
    private val audit = mutableListOf<OrgAuditRecord>()
    private val member = TestCallers.memberA.memberId
    private val admin = TestCallers.adminA.memberId
    private val overrides: Module = module {
        includes(TestCallers.overrides(clock))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<OrgAuditTrail> { OrgAuditTrail { audit += it } }
        single<MemberLogins> { Logins }
    }
    private val modules = listOf(SecretsModule, UsageModule)

    /** A login for org A's member and admin; nobody else. */
    private object Logins : MemberLogins {
        private val names = mapOf(TestCallers.memberA.memberId to "member", TestCallers.adminA.memberId to "admin")

        override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> = emptyMap()

        override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
            memberIds.mapNotNull { id -> names[id]?.let { id to it } }.toMap()
    }

    /**
     * Writes a hold as the gateway leaves it, by the database's clock: held [heldHoursAgo] hours ago, run out an hour
     * later (or still within its lease when [active]).
     */
    private suspend fun TestServer.hold(
        micros: Long,
        orgId: Uuid = TestCallers.orgA,
        memberId: Uuid = member,
        heldHoursAgo: Int = 3,
        active: Boolean = false,
    ): Uuid {
        val id = Uuid.random()
        val expires = if (active) databaseNowPlus(5.minutes) else databaseNowPlus((-(heldHoursAgo - 1)).hours)
        database.superuserJdbc.query {
            UsageHolds.insert {
                it[callId] = id
                it[this.orgId] = orgId
                it[this.memberId] = memberId
                it[workflowId] = "review"
                it[launchId] = "lch_1"
                it[this.micros] = micros
                it[expiresAt] = expires
                it[heldAt] = databaseNowPlus((-heldHoursAgo).hours)
            }
        }
        return id
    }

    /** Writes a correction directly, [reconciledMinutesAgo] minutes ago, of a hold held [heldDaysAgo] days ago. */
    private suspend fun TestServer.reconciliation(heldDaysAgo: Int = 1, reconciledMinutesAgo: Int = 1): Uuid {
        val id = Uuid.random()
        database.superuserJdbc.query {
            UsageReconciliations.insert {
                it[callId] = id
                it[orgId] = TestCallers.orgA
                it[memberId] = member
                it[heldAt] = databaseNowPlus((-heldDaysAgo).days)
                it[previousMicros] = 10L
                it[micros] = 5L
                it[evidence] = "PROVIDER_RECORD"
                it[reference] = "ref"
                it[reason] = "why"
                it[reconciledBy] = admin
                it[reconciledAt] = databaseNowPlus((-reconciledMinutesAgo).minutes)
            }
        }
        return id
    }

    private suspend fun TestServer.read(path: String, token: String = "admin-a"): HttpResponse =
        client.get(path) { bearerAuth(token) }

    /** A correction of the charge shown (5 000) to 1 000, on an invoice line. */
    private val valid =
        ReconcileUsageRequest(5_000, 1_000, "PROVIDER_RECORD", "Console invoice 2026-11", "Invoice line checked")

    private suspend fun TestServer.reconcile(
        id: Any,
        token: String = "admin-a",
        request: ReconcileUsageRequest = valid,
    ): HttpResponse = client.post("/api/v1/usage/reservations/$id/reconcile") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    /** Whether a call of [micros] is admitted under an org limit of [limit], now by the wall clock. */
    private suspend fun TestServer.admit(micros: Long, limit: Long): Admission {
        val now = Clock.System.now()
        val window = HoldWindow(now - 1.days, now, now + 5.minutes)
        val hold = SpendHold(TestCallers.orgA, member, Uuid.random(), micros)
        return koin.get<UsageRepository>().holdWithin(hold, SpendLimits(limit, null), window)
    }

    private suspend fun errorType(response: HttpResponse): String =
        Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("type")
            .jsonPrimitive.content

    @Test
    fun onlyAnAdministratorSignedInReviews() = serverTest(modules, overrides) { server ->
        val id = server.hold(5_000)
        val reads = listOf(
            "/api/v1/usage/pending",
            "/api/v1/usage/reconciliations",
            "/api/v1/usage/reservations/$id",
        )
        // Only an owner or admin, a person: a member, a key even with the scopes, an agent and a device are refused.
        for (token in listOf("member-a", "key-a", "agent-a", "device-a")) {
            for (path in reads) assertEquals(HttpStatusCode.Forbidden, server.read(path, token).status, "$token $path")
            assertEquals(HttpStatusCode.Forbidden, server.reconcile(id, token).status, token)
        }
        for (path in reads) assertEquals(HttpStatusCode.Unauthorized, server.client.get(path).status, path)
        assertEquals(HttpStatusCode.OK, server.read(reads[0], "cli-read").status, "A person's token with usage:read")
        val stale = server.reconcile(id, "stale-admin-a")
        assertEquals(HttpStatusCode.Forbidden, stale.status)
        assertEquals("step_up_required", errorType(stale))
        assertEquals(emptyList(), audit)
    }

    @Test
    fun anExpiredHoldIsPendingUntilItsCorrectionReplacesItsCharge() = serverTest(modules, overrides) { server ->
        val id = server.hold(5_000)
        val active = server.hold(700, active = true)
        server.hold(900, orgId = TestCallers.orgB, memberId = TestCallers.adminB.memberId)
        // The lost call's charge counts: 5 000 spent and 700 in flight of 6 000, a call of 2 000 does not fit.
        assertEquals(Admission.Insufficient(SpendScope.ORG, 6_000, 300), server.admit(2_000, 6_000))

        val pending = server.read("/api/v1/usage/pending")
        assertEquals("no-store", pending.headers[HttpHeaders.CacheControl])
        val page = pending.body<PendingUsagePage>()
        assertNull(page.next)
        val item = page.items.single()
        assertEquals(
            listOf(id.toString(), "member", "review", "lch_1"),
            listOf(item.id, item.member, item.workflowId, item.launchId),
        )
        assertEquals(5_000, item.micros)
        assertNull(item.providerRequestId)
        assertNotNull(server.read("/api/v1/usage/reservations/$id").body<UsageReviewDto>().pending)
        // A hold still within its lease is no review's.
        assertEquals(HttpStatusCode.NotFound, server.read("/api/v1/usage/reservations/$active").status)
        val early = server.reconcile(active, request = valid.copy(expectedMicros = 700))
        assertEquals(HttpStatusCode.NotFound, early.status)

        val corrected = server.reconcile(id)
        assertEquals(HttpStatusCode.OK, corrected.status)
        val record = corrected.body<UsageReconciliationDto>()
        assertEquals(listOf(5_000L, 1_000L), listOf(record.previousMicros, record.micros))
        assertEquals(
            listOf("member", "admin", "PROVIDER_RECORD"),
            listOf(record.member, record.reconciledBy, record.source),
        )
        assertEquals(item.heldAt, record.heldAt)
        assertEquals(listOf("usage.reconciled"), audit.map { it.event })
        assertEquals(mapOf("previous_micros" to "5000", "micros" to "1000"), audit.single().details)
        // The hold now charges the verified total where it was held: the call of 2 000 fits.
        assertEquals(Admission.Admitted, server.admit(2_000, 6_000))

        assertEquals(emptyList(), server.read("/api/v1/usage/pending").body<PendingUsagePage>().items)
        val review = server.read("/api/v1/usage/reservations/$id").body<UsageReviewDto>()
        assertNull(review.pending)
        assertEquals(record, review.reconciliation)
        val history = server.read("/api/v1/usage/reconciliations").body<UsageReconciliationsPage>()
        assertEquals(listOf(record), history.items)

        // The same correction again answers the record, with no other change; a different one is refused.
        val retried = server.reconcile(id)
        assertEquals(HttpStatusCode.OK, retried.status)
        assertEquals(record, retried.body<UsageReconciliationDto>())
        assertEquals(1, audit.size)
        val other = server.reconcile(id, request = valid.copy(micros = 2_000))
        assertEquals(HttpStatusCode.Conflict, other.status)
        assertEquals("reconciled", errorType(other))
    }

    @Test
    fun aCorrectionOfAChargeThatChangedOrOutOfBoundsIsRefused() = serverTest(modules, overrides) { server ->
        val id = server.hold(5_000)
        val changed = listOf(
            server.reconcile(id, request = valid.copy(expectedMicros = 4_999)),
            server.reconcile(id, request = valid.copy(expectedProviderRequestId = "req_1")),
        )
        for (response in changed) {
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("stale_reconciliation", errorType(response))
        }
        val invalid = listOf(
            server.reconcile(id, request = valid.copy(source = "QUOTA_PERCENTAGE")),
            server.reconcile(id, request = valid.copy(reason = "two\nlines")),
            server.reconcile(id, request = valid.copy(reason = " ")),
            server.reconcile(id, request = valid.copy(micros = -1)),
            server.reconcile(id, request = valid.copy(micros = 100_000_000_000_001)),
            server.reconcile(id, request = valid.copy(expectedMicros = -1)),
            server.reconcile(id, request = valid.copy(expectedProviderRequestId = "https://example.org/x")),
        )
        for (response in invalid) {
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals("invalid_reconciliation", errorType(response))
        }
        for (path in listOf("/api/v1/usage/pending?member=x", "/api/v1/usage/reconciliations?before=x")) {
            assertEquals("invalid_reconciliation", errorType(server.read(path)), path)
        }
        val unknown = Uuid.random()
        for (path in listOf("/api/v1/usage/pending", "/api/v1/usage/reconciliations")) {
            assertEquals(HttpStatusCode.NotFound, server.read("$path?before=$unknown").status, path)
        }
        assertEquals(HttpStatusCode.NotFound, server.read("/api/v1/usage/reservations/x").status)
        assertEquals(HttpStatusCode.NotFound, server.read("/api/v1/usage/reservations/$unknown").status)
        assertEquals(HttpStatusCode.NotFound, server.reconcile("x").status)
        assertEquals(HttpStatusCode.NotFound, server.reconcile(unknown).status)
        // The total may be 0 or $100 000 000.
        assertEquals(HttpStatusCode.OK, server.reconcile(id, request = valid.copy(micros = 0)).status)
        val top = server.hold(5_000)
        val most = server.reconcile(top, request = valid.copy(micros = 100_000_000_000_000))
        assertEquals(HttpStatusCode.OK, most.status)
    }

    @Test
    fun anotherOrganizationNeitherSeesNorCorrectsTheHolds() = serverTest(modules, overrides) { server ->
        val id = server.hold(5_000)
        val own = server.hold(900, orgId = TestCallers.orgB, memberId = TestCallers.adminB.memberId)
        val theirs = server.read("/api/v1/usage/pending", "admin-b").body<PendingUsagePage>().items.single()
        // Its member has no login known here: named by id.
        assertEquals(listOf(own.toString(), TestCallers.adminB.memberId.toString()), listOf(theirs.id, theirs.member))
        assertEquals(HttpStatusCode.NotFound, server.read("/api/v1/usage/reservations/$id", "admin-b").status)
        assertEquals(HttpStatusCode.NotFound, server.read("/api/v1/usage/pending?before=$id", "admin-b").status)
        assertEquals(HttpStatusCode.NotFound, server.reconcile(id, "admin-b").status)
        assertEquals(HttpStatusCode.OK, server.reconcile(id).status)
        assertEquals(HttpStatusCode.NotFound, server.read("/api/v1/usage/reservations/$id", "admin-b").status)
        val history = server.read("/api/v1/usage/reconciliations", "admin-b").body<UsageReconciliationsPage>()
        assertEquals(emptyList(), history.items)
    }

    @Test
    fun theReviewPagesFiftyAtATimeNewestFirstOfTheOrgOrAMember() = serverTest(modules, overrides) { server ->
        val mine = (1..51).map { server.hold(10, heldHoursAgo = 2 + it) }
        val theirs = server.hold(10, memberId = admin, heldHoursAgo = 2)
        val first = server.read("/api/v1/usage/pending").body<PendingUsagePage>()
        assertEquals(listOf(theirs) + mine.take(49), first.items.map { Uuid.parse(it.id) })
        assertEquals(mine[48].toString(), first.next)
        val second = server.read("/api/v1/usage/pending?before=${first.next}").body<PendingUsagePage>()
        assertEquals(mine.drop(49), second.items.map { Uuid.parse(it.id) })
        assertNull(second.next)
        val members = server.read("/api/v1/usage/pending?member=$member").body<PendingUsagePage>()
        assertEquals(mine.take(50), members.items.map { Uuid.parse(it.id) })

        val corrections = (1..51).map { server.reconciliation(reconciledMinutesAgo = it) }
        val history = server.read("/api/v1/usage/reconciliations").body<UsageReconciliationsPage>()
        assertEquals(corrections.take(50), history.items.map { Uuid.parse(it.id) })
        val rest = server.read("/api/v1/usage/reconciliations?before=${history.next}").body<UsageReconciliationsPage>()
        assertEquals(corrections.drop(50), rest.items.map { Uuid.parse(it.id) })
        val ofAdmin = server.read("/api/v1/usage/reconciliations?member=$admin").body<UsageReconciliationsPage>()
        assertEquals(emptyList(), ofAdmin.items)
    }

    @Test
    fun holdsAndCorrectionsPastTheRetentionAreGone() = serverTest(modules, overrides) { server ->
        val old = server.hold(5_000, heldHoursAgo = 91 * 24)
        assertEquals(emptyList(), server.read("/api/v1/usage/pending").body<PendingUsagePage>().items)
        assertEquals(HttpStatusCode.NotFound, server.reconcile(old).status)
        val kept = server.reconciliation(heldDaysAgo = 89)
        val outlived = server.reconciliation(heldDaysAgo = 91)
        assertEquals(HttpStatusCode.NotFound, server.read("/api/v1/usage/reservations/$outlived").status)
        val review = server.read("/api/v1/usage/reservations/$kept").body<UsageReviewDto>()
        assertIs<UsageReconciliationDto>(review.reconciliation)
        assertEquals(HttpStatusCode.OK, server.reconcile(server.hold(5_000)).status)
        val rows = server.database.superuserJdbc.query { UsageReconciliations.selectAll().count() }
        assertEquals(2L, rows, "A correction drops those past the retention")
    }
}
