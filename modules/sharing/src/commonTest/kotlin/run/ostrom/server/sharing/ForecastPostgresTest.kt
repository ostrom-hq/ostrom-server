// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing

import run.ostrom.server.kernel.database.PurgeDateRefusedException
import run.ostrom.server.sharing.domain.QuotaHistoryRepository
import run.ostrom.server.sharing.domain.QuotaWindow
import run.ostrom.server.sharing.domain.QuotaWindowRepository
import run.ostrom.server.sharing.presentation.ForecastSnapshotResponse
import run.ostrom.server.sharing.presentation.SharedAccountResponse
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.SQLError
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Real PostgreSQL and HTTP: TeamCode's `ForecastPersistenceTest` and `ForecastRoutesTest`. The history keeps one
 * reading per minute and window (the newest), is confined to its org, and is pruned after seven days; the route answers
 * the org's owners and admins signed in, never another org's account, never cached.
 */
class ForecastPostgresTest : SharingPostgresHarness() {
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    private suspend fun TestServer.shared(token: String = "admin-a", id: String = "acct-uuid-1"): Uuid =
        Uuid.parse(shareAs(token, id).body<SharedAccountResponse>().id)

    private suspend fun TestServer.forecasts(token: String?, query: String = ""): HttpResponse =
        client.get("/api/v1/forecasts$query") { token?.let { bearerAuth(it) } }

    private suspend fun HttpResponse.errorType(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject.getValue("error").jsonObject.getValue("type")
            .jsonPrimitive.content

    private fun reading(window: String, age: Int, used: Double, reset: Instant? = now + 1.hours) =
        QuotaWindow(window, used, reset, now - age.minutes)

    /** Readings of [window] each minute up to now, and the latest as the account's current window. */
    private suspend fun TestServer.observed(orgId: Uuid, account: Uuid, window: String, vararg used: Double) {
        val readings = used.mapIndexed { index, u -> reading(window, used.size - 1 - index, u) }
        koin.get<QuotaHistoryRepository>().record(orgId, account, readings)
        koin.get<QuotaWindowRepository>().put(orgId, account, listOf(readings.last()))
    }

    @Test
    fun anAdminReadsTheForecastsOfTheOrgsAccountsWithExplicitUnknowns() = serverTest(modules, overrides) { server ->
        with(server) {
            val empty = forecasts("admin-a")
            assertEquals(HttpStatusCode.OK, empty.status)
            assertNull(empty.body<ForecastSnapshotResponse>().account)
            val selected = shared()
            observed(Sharing.orgA, selected, "five_hour", .1, .2, .3)
            observed(Sharing.orgA, selected, "seven_day", .1, .1, .1)
            val unknown = reading("model:opus", 0, .1, reset = null)
            koin.get<QuotaHistoryRepository>().record(Sharing.orgA, selected, listOf(unknown))
            koin.get<QuotaWindowRepository>().put(Sharing.orgA, selected, listOf(unknown))

            val response = forecasts("admin-a", "?account=$selected")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val text = response.bodyAsText()
            assertFalse("acct-uuid-1" in text, "Never the provider account id")
            assertFalse("secret" in text, "Never a token")
            val snapshot = response.body<ForecastSnapshotResponse>()
            assertEquals(selected.toString(), snapshot.account!!.id)
            assertEquals("ready" to false, snapshot.account!!.status to snapshot.account!!.paused)
            assertEquals("lyes@example.com", snapshot.account!!.email)
            assertEquals(0.95, snapshot.pressureThreshold)
            assertFalse(snapshot.truncated)
            val byWindow = snapshot.windows.associateBy { it.window }
            assertEquals(listOf("five_hour", "model:opus", "seven_day"), snapshot.windows.map { it.window })
            val fiveHour = byWindow.getValue("five_hour")
            assertEquals("exhausts_first", fiveHour.state)
            assertEquals(600.0, fiveHour.pointsPerHour!!, 1e-6)
            assertEquals(3, fiveHour.learningSamples)
            assertEquals(120_000L, fiveHour.learningSpanMillis)
            assertEquals(listOf(.1, .2, .3), fiveHour.segments.single().map { it.utilization })
            assertEquals("idle", byWindow.getValue("seven_day").state)
            val model = byWindow.getValue("model:opus")
            assertEquals("unknown", model.state)
            assertNull(model.resetsAt)
            assertNull(model.pointsPerHour)
            assertNull(model.reachesThresholdAt)
            val first = forecasts("admin-a").body<ForecastSnapshotResponse>()
            assertEquals(snapshot, first.copy(generatedAt = snapshot.generatedAt), "The first account by default")
        }
    }

    @Test
    fun onlyAnOwnerOrAnAdminSignedInReadsThem() = serverTest(modules, overrides) { server ->
        with(server) {
            shared()
            assertEquals(HttpStatusCode.Unauthorized, forecasts(null).status)
            // The person's sign-in on a workstation, as the instance's administration.
            assertEquals(HttpStatusCode.OK, forecasts("admin-cli-a").status)
            val refusedTokens =
                listOf("member-a", "member-cli-a", "agent-a", "device-a", "reader-a", "personal-a", "cli-a")
            for (token in refusedTokens) {
                val refused = forecasts(token)
                assertEquals(HttpStatusCode.Forbidden, refused.status, token)
                assertEquals("no-store", refused.headers[HttpHeaders.CacheControl], token)
            }
        }
    }

    @Test
    fun anAccountGivenTwiceUnknownOrOfAnotherOrgIsRefused() = serverTest(modules, overrides) { server ->
        with(server) {
            val account = shared()
            val twice = forecasts("admin-a", "?account=$account&account=$account")
            assertEquals(HttpStatusCode.BadRequest, twice.status)
            assertEquals("invalid_account", twice.errorType())
            assertEquals(HttpStatusCode.NotFound, forecasts("admin-a", "?account=${Uuid.random()}").status)
            assertEquals(HttpStatusCode.NotFound, forecasts("admin-a", "?account=not-an-id").status)
            // Another organization cannot read the account's forecasts, nor see it listed.
            assertEquals(HttpStatusCode.NotFound, forecasts("admin-b", "?account=$account").status)
            val other = forecasts("admin-b").body<ForecastSnapshotResponse>()
            assertTrue(other.accounts.none { it.id == account.toString() })
        }
    }

    @Test
    fun theHistoryKeepsTheNewestReadingOfEachMinuteForTheOrgsOwnAccounts() = serverTest(modules, overrides) { server ->
        with(server) {
            val history = koin.get<QuotaHistoryRepository>()
            val account = shared()
            val minute = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() / 60_000 * 60_000)
            val newer = QuotaWindow("five_hour", .3, now + 1.hours, minute + 30.seconds)
            history.record(Sharing.orgA, account, listOf(newer))
            val older = newer.copy(utilization = .2, observedAt = minute + 10.seconds)
            val newest = newer.copy(utilization = .4, observedAt = minute + 40.seconds)
            history.record(Sharing.orgA, account, listOf(older))
            history.record(Sharing.orgA, account, listOf(newest))
            // Not a reading (a reset before it), and an empty list: nothing written.
            history.record(Sharing.orgA, account, listOf(newer.copy(resetsAt = minute)))
            history.record(Sharing.orgA, account, emptyList())
            suspend fun read(org: Uuid) =
                history.recent(org, account, listOf("five_hour"), now - 1.hours, now + 1.hours, 10)
            assertEquals(listOf(.4), read(Sharing.orgA).map { it.utilization })

            // Another organization writes nothing on the account and reads none of it.
            history.record(Sharing.orgB, account, listOf(newer.copy(observedAt = minute - 1.minutes)))
            assertEquals(1, read(Sharing.orgA).size)
            assertEquals(emptyList(), read(Sharing.orgB))
        }
    }

    @Test
    fun readingsOlderThanSevenDaysArePrunedAcrossOrganizationsAndNothingYounger() =
        serverTest(modules, overrides) { server ->
        with(server) {
            val history = koin.get<QuotaHistoryRepository>()
            val a = shared()
            val b = shared("admin-b", "acct-uuid-b")
            history.record(Sharing.orgA, a, listOf(reading("five_hour", (8.days).inWholeMinutes.toInt(), .1)))
            history.record(Sharing.orgB, b, listOf(reading("five_hour", (9.days).inWholeMinutes.toInt(), .1)))
            history.record(Sharing.orgA, a, listOf(reading("five_hour", (6.days).inWholeMinutes.toInt(), .1)))
            assertEquals(1L, history.prune(now - 7.days, 1), "At most the limit")
            assertEquals(1L, history.prune(now - 7.days, 1000))
            assertEquals(0L, history.prune(now, 1000), "Never younger than the retention, whatever the date")
            assertEquals(1, history.recent(Sharing.orgA, a, listOf("five_hour"), now - 30.days, now, 10).size)
            for (date in listOf(now + 1.days, Instant.DISTANT_PAST)) {
                assertFailsWith<PurgeDateRefusedException>("$date") { history.prune(date, 1000) }
            }
            // Straight to the function, as the serving role: a date or a limit it cannot bound is refused.
            val direct = driver.fetchAll("SELECT sharing_quota_history_purge(now(), 10) AS n").getOrThrow()
            assertEquals(0L, direct.first().get("n").asLong())
            val refused =
                listOf("now() + INTERVAL '1 day', 10", "'infinity', 10", "NULL, 10", "now(), 0", "now(), 10001")
            for (call in refused) {
                assertFailsWith<SQLError>(call) {
                    driver.fetchAll("SELECT sharing_quota_history_purge($call) AS n").getOrThrow()
                }
            }
        }
    }
}
