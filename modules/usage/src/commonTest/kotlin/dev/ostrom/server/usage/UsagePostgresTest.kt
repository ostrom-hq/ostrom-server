// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.usage

import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import dev.ostrom.server.usage.domain.SpendReader
import dev.ostrom.server.usage.domain.UsageCursor
import dev.ostrom.server.usage.domain.UsageGroupBy
import dev.ostrom.server.usage.domain.UsageKind
import dev.ostrom.server.usage.domain.UsageRecorder
import dev.ostrom.server.usage.domain.UsageRepository
import dev.ostrom.server.usage.presentation.UsageAcceptedResponse
import dev.ostrom.server.usage.presentation.UsageEventRequest
import dev.ostrom.server.usage.presentation.UsageEventsPageResponse
import dev.ostrom.server.usage.presentation.UsageEventsRequest
import dev.ostrom.server.usage.presentation.UsageSummaryResponse
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.withTimeout
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Real PostgreSQL and real HTTP: the ledger, its reads, its live feed, roles and org isolation. */
class UsagePostgresTest {
    private val clock = TestClock()
    private val overrides: Module = module {
        includes(TestCallers.overrides(clock))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
    }
    private val modules = listOf(SecretsModule, UsageModule)
    private val orgA = "/v1/orgs/${TestCallers.orgA}/usage"
    private val orgB = "/v1/orgs/${TestCallers.orgB}/usage"

    private suspend fun TestServer.declare(token: String, vararg events: UsageEventRequest) = client.post("/v1/usage/events") {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(UsageEventsRequest(events.toList()))
    }

    @Test
    fun theCliDeclaresEventsThatTheOrgThenReads() = serverTest(modules, overrides) { server ->
        val declared = server.declare(
            "member-a",
            UsageEventRequest("skill", "deploy", sessionId = "s-1", durationMillis = 1500),
            UsageEventRequest("mcp", "grafana/query_loki_logs", at = "2026-10-30T11:00:00Z"),
            UsageEventRequest("hook", "PreToolUse", outcome = "denied"),
        )
        assertEquals(HttpStatusCode.Accepted, declared.status, declared.bodyAsText())
        assertEquals(3, declared.body<UsageAcceptedResponse>().accepted)

        val stored = server.driver.fetchAll("SELECT org_id, member_id, cost_micros FROM usage_events").getOrThrow()
        assertEquals(3, stored.size)
        assertTrue(stored.all { it.get("org_id").asUuid() == TestCallers.orgA }, "The org comes from the token")
        assertTrue(stored.all { it.get("member_id").asUuid() == TestCallers.memberA.memberId }, "The member too")
        assertTrue(stored.all { it.get("cost_micros").asLong() == 0L }, "Clients never declare a cost")

        val summary = server.client.get("$orgA/summary?days=1&groupBy=kind") { bearerAuth("admin-a") }.body<UsageSummaryResponse>()
        assertEquals("kind", summary.groupBy)
        assertEquals(setOf("SKILL", "MCP", "HOOK"), summary.groups.map { it.key }.toSet())
        assertEquals(3L, summary.total.events)

        val page = server.client.get("$orgA/events?limit=2") { bearerAuth("member-a") }.body<UsageEventsPageResponse>()
        assertEquals(2, page.events.size)
        val next = requireNotNull(page.next) { "A full page has a next cursor" }
        val rest = server.client.get("$orgA/events") {
            url.parameters.append("before", next)
            bearerAuth("member-a")
        }.body<UsageEventsPageResponse>()
        assertEquals(1, rest.events.size)
        assertEquals(null, rest.next)
        assertEquals(3, (page.events + rest.events).map { it.id }.toSet().size, "Keyset paging neither repeats nor skips")
        assertEquals("mcp", rest.events.single().kind, "Oldest last")
    }

    @Test
    fun clientsCannotDeclareModelUsageNorMalformedBatches() = serverTest(modules, overrides) { server ->
        assertEquals(HttpStatusCode.Unauthorized, server.client.post("/v1/usage/events").status)
        assertEquals(HttpStatusCode.BadRequest, server.declare("member-a", UsageEventRequest("model", "claude-opus-4-1")).status)
        assertEquals(HttpStatusCode.BadRequest, server.declare("member-a", UsageEventRequest("payment", "x")).status)
        assertEquals(HttpStatusCode.BadRequest, server.declare("member-a", UsageEventRequest("skill", "")).status)
        val tooMany = server.declare("member-a", *Array(501) { UsageEventRequest("hook", "Stop") })
        assertEquals(HttpStatusCode.PayloadTooLarge, tooMany.status)
        val tooBig = server.client.post("/v1/usage/events") {
            bearerAuth("member-a")
            contentType(ContentType.Application.Json)
            setBody("""{"events":[],"padding":"${"x".repeat(1_048_577)}"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, tooBig.status)
        assertEquals(0, server.driver.fetchAll("SELECT id FROM usage_events").getOrThrow().size)
    }

    @Test
    fun theServerRecordsModelUsageAndReadsSpend() = serverTest(modules, overrides) { server ->
        val recorder = server.koin.get<UsageRecorder>()
        val account = Uuid.random()
        recorder.record(modelEvent(1_500, memberId = TestCallers.adminA.memberId, accountId = account))
        recorder.record(modelEvent(2_500, memberId = TestCallers.memberA.memberId, provider = "openrouter", name = "gpt-5"))
        recorder.record(modelEvent(4_000, memberId = null, provider = null))
        recorder.record(modelEvent(8_000, memberId = TestCallers.memberA.memberId, at = TestCallers.epoch - 40.days))
        recorder.record(modelEvent(16_000, orgId = TestCallers.orgB, memberId = TestCallers.adminB.memberId, accountId = account))

        val spend = server.koin.get<SpendReader>()
        val month = TestCallers.epoch - 30.days
        assertEquals(8_000L, spend.spentMicros(TestCallers.orgA, null, month))
        assertEquals(2_500L, spend.spentMicros(TestCallers.orgA, TestCallers.memberA.memberId, month))
        assertEquals(0L, spend.spentMicros(TestCallers.orgA, Uuid.random(), month))
        assertEquals(17_500L, spend.accountSpentMicros(account, month), "An account is pooled across its uses")

        suspend fun groups(groupBy: String, token: String = "admin-a") =
            server.client.get("$orgA/summary?days=30&groupBy=$groupBy") { bearerAuth(token) }.body<UsageSummaryResponse>()
                .groups.associate { it.key to it.costMicros }
        assertEquals(
            mapOf(null to 4_000L, TestCallers.memberA.memberId.toString() to 2_500L, TestCallers.adminA.memberId.toString() to 1_500L),
            groups("member"),
        )
        assertEquals(mapOf<String?, Long>("MODEL" to 8_000L), groups("kind"))
        assertEquals(mapOf<String?, Long>("claude-sonnet-4-5" to 5_500L, "gpt-5" to 2_500L), groups("model"))
        assertEquals(mapOf("anthropic" to 1_500L, "openrouter" to 2_500L, null to 4_000L), groups("provider"))
        assertEquals(mapOf(null to 6_500L, account.toString() to 1_500L), groups("account"))
        assertEquals(mapOf<String?, Long>(TestCallers.memberA.memberId.toString() to 2_500L), groups("member", "member-a"), "A member sees only themselves")

        val summary = server.client.get("$orgA/summary?days=30") { bearerAuth("admin-a") }.body<UsageSummaryResponse>()
        assertEquals(8_000L, summary.total.costMicros)
        assertEquals(300L, summary.total.inputTokens)
        assertEquals(HttpStatusCode.BadRequest, server.client.get("$orgA/summary?days=91") { bearerAuth("admin-a") }.status)
        assertEquals(HttpStatusCode.BadRequest, server.client.get("$orgA/summary?groupBy=team") { bearerAuth("admin-a") }.status)
        assertEquals(HttpStatusCode.BadRequest, server.client.get("$orgA/events?before=soon") { bearerAuth("admin-a") }.status)
    }

    @Test
    fun theRepositoryPagesTiesByIdAndPurgesInBatches() = serverTest(modules, overrides) { server ->
        val repository = server.koin.get<UsageRepository>()
        val sameInstant = List(3) { modelEvent(it.toLong()) }
        repository.insert(sameInstant + modelEvent(9, at = TestCallers.epoch - 500.days))
        repository.insert(emptyList())

        val first = repository.list(TestCallers.orgA, null, null, 2)
        val second = repository.list(TestCallers.orgA, null, UsageCursor(first.last().at, first.last().id), 2)
        assertEquals(sameInstant.map { it.id }.toSet(), (first + second.take(1)).map { it.id }.toSet())
        assertEquals(9L, second.last().costMicros)
        assertEquals(listOf(9L), repository.list(TestCallers.orgA, null, UsageCursor(TestCallers.epoch, null), 10).map { it.costMicros })
        assertEquals(sameInstant.first(), repository.list(TestCallers.orgA, TestCallers.adminA.memberId, null, 10).first { it.id == sameInstant.first().id })
        assertEquals(UsageKind.MODEL, first.first().kind)
        assertEquals(1, repository.summary(TestCallers.orgA, TestCallers.adminA.memberId, TestCallers.epoch - 1.days, UsageGroupBy.KIND, 10).size)

        assertEquals(1L, repository.purge(TestCallers.epoch - 400.days, 10))
        assertEquals(0L, repository.purge(TestCallers.epoch - 400.days, 10))
        assertEquals(3, repository.list(TestCallers.orgA, null, null, 10).size)
    }

    @Test
    fun anotherOrgNeitherSeesNorReadsTheUsage() = serverTest(modules, overrides) { server ->
        server.koin.get<UsageRecorder>().record(modelEvent(1_000))

        assertEquals(HttpStatusCode.NotFound, server.client.get("$orgA/summary") { bearerAuth("admin-b") }.status)
        assertEquals(HttpStatusCode.NotFound, server.client.get("$orgA/events") { bearerAuth("admin-b") }.status)
        assertEquals(HttpStatusCode.NotFound, server.client.get("$orgA/live") { bearerAuth("admin-b") }.status)
        assertEquals(HttpStatusCode.NotFound, server.client.get("/v1/orgs/not-a-uuid/usage/summary") { bearerAuth("admin-a") }.status)
        val own = server.client.get("$orgB/summary?days=1") { bearerAuth("admin-b") }.body<UsageSummaryResponse>()
        assertEquals(emptyList(), own.groups)
        assertEquals(emptyList(), server.client.get("$orgB/events") { bearerAuth("admin-b") }.body<UsageEventsPageResponse>().events)
        server.declare("admin-b", UsageEventRequest("skill", "org-b-skill"))
        val orgAEvents = server.client.get("$orgA/events") { bearerAuth("admin-a") }.body<UsageEventsPageResponse>().events
        assertEquals(listOf("claude-sonnet-4-5"), orgAEvents.map { it.name }, "Org B's declaration never lands in org A")
    }

    @Test
    fun theLiveFeedStreamsTheOrgsNewEvents() = serverTest(modules, overrides) { server ->
        val recorder = server.koin.get<UsageRecorder>()
        server.client.prepareGet("$orgA/live") { bearerAuth("member-a") }.execute { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/event-stream"))
            val stream = response.bodyAsChannel()
            withTimeout(30.seconds) {
                assertEquals(": connected", stream.readUTF8Line())
                assertEquals("", stream.readUTF8Line())
                recorder.record(modelEvent(111, memberId = TestCallers.adminA.memberId))
                recorder.record(modelEvent(222, memberId = TestCallers.memberA.memberId, at = TestCallers.epoch + 1.minutes))
                val line = requireNotNull(stream.readUTF8Line())
                assertTrue(line.startsWith("data: {"), line)
                assertTrue("\"costMicros\":222" in line, "A member only follows their own events: $line")
            }
        }
        server.client.prepareGet("$orgA/live") { bearerAuth("admin-a") }.execute { response ->
            val stream = response.bodyAsChannel()
            withTimeout(30.seconds) {
                assertEquals(": connected", stream.readUTF8Line())
                stream.readUTF8Line()
                recorder.record(modelEvent(333, memberId = TestCallers.memberA.memberId))
                assertTrue("\"costMicros\":333" in requireNotNull(stream.readUTF8Line()), "An admin follows every member")
            }
        }
    }
}
