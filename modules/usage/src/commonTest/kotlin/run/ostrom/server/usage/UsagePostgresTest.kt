// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.usage

import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.secrets.infrastructure.executeInOrg
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.domain.SpendReader
import run.ostrom.server.usage.domain.UsageCursor
import run.ostrom.server.usage.domain.UsageGroupBy
import run.ostrom.server.usage.domain.UsageKind
import run.ostrom.server.usage.domain.UsageRecorder
import run.ostrom.server.usage.domain.UsageRepository
import run.ostrom.server.usage.domain.UsageWindow
import run.ostrom.server.usage.presentation.UsageAcceptedResponse
import run.ostrom.server.usage.presentation.UsageEventRequest
import run.ostrom.server.usage.presentation.UsageEventsPageResponse
import run.ostrom.server.usage.presentation.UsageEventsRequest
import run.ostrom.server.usage.presentation.UsageResponse
import run.ostrom.server.usage.presentation.UsageSummaryResponse
import io.github.smyrgeorge.sqlx4k.Statement
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
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.withTimeout
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
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

    // The org comes from the token: admin-a and member-a read org A, admin-b org B.
    private val orgA = "/api/v1/usage"
    private val orgB = "/api/v1/usage"

    private suspend fun TestServer.declare(token: String, vararg events: UsageEventRequest) =
        client.post("/api/v1/usage/events") {
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

        val stored = server.driver.fetchInOrg(
            TestCallers.orgA,
            Statement.create("SELECT org_id, member_id, cost_micros FROM usage_events"),
        ).getOrThrow()
        assertEquals(3, stored.size)
        assertTrue(stored.all { it.get("org_id").asUuid() == TestCallers.orgA }, "The org comes from the token")
        assertTrue(stored.all { it.get("member_id").asUuid() == TestCallers.memberA.memberId }, "The member too")
        assertTrue(stored.all { it.get("cost_micros").asLong() == 0L }, "Clients never declare a cost")

        val summary = server.client.get("$orgA/summary?days=1&groupBy=kind") { bearerAuth("admin-a") }
            .body<UsageSummaryResponse>()
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
        assertEquals(
            3,
            (page.events + rest.events).map { it.id }.toSet().size,
            "Keyset paging neither repeats nor skips",
        )
        assertEquals("mcp", rest.events.single().kind, "Oldest last")
    }

    @Test
    fun aStreamedBatchWithoutContentLengthIsAccepted() = serverTest(modules, overrides) { server ->
        val streamed = server.client.post("$orgA/events") {
            bearerAuth("member-a")
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override val contentType = ContentType.Application.Json

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        channel.writeStringUtf8("""{"events":[{"kind":"skill","name":"deploy"}]}""")
                    }
                },
            )
        }
        assertEquals(HttpStatusCode.Accepted, streamed.status, streamed.bodyAsText())
        assertEquals(1, streamed.body<UsageAcceptedResponse>().accepted)
    }

    @Test
    fun clientsCannotDeclareModelUsageNorMalformedBatches() = serverTest(modules, overrides) { server ->
        assertEquals(HttpStatusCode.Unauthorized, server.client.post("/api/v1/usage/events").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            server.declare("member-a", UsageEventRequest("model", "claude-opus-4-1")).status,
        )
        assertEquals(HttpStatusCode.BadRequest, server.declare("member-a", UsageEventRequest("payment", "x")).status)
        assertEquals(HttpStatusCode.BadRequest, server.declare("member-a", UsageEventRequest("skill", "")).status)
        // Instants the parser reads but PostgreSQL cannot store: a 400, never the database's 500.
        for (at in listOf("+300000-01-01T00:00:00Z", "-300000-01-01T00:00:00Z")) {
            val beyond = UsageEventRequest("skill", "deploy", at = at)
            assertEquals(HttpStatusCode.BadRequest, server.declare("member-a", beyond).status, at)
        }
        val tooMany = server.declare("member-a", *Array(501) { UsageEventRequest("hook", "Stop") })
        assertEquals(HttpStatusCode.PayloadTooLarge, tooMany.status)
        val tooBig = server.client.post("/api/v1/usage/events") {
            bearerAuth("member-a")
            contentType(ContentType.Application.Json)
            setBody("""{"events":[],"padding":"${"x".repeat(1_048_577)}"}""")
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, tooBig.status)
        val chunked = server.client.post("/api/v1/usage/events") {
            bearerAuth("member-a")
            // No Content-Length: the body streams in chunks, and is still cut at 1 MiB.
            setBody(
                object : OutgoingContent.WriteChannelContent() {
                    override val contentType = ContentType.Application.Json

                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        channel.writeStringUtf8("""{"events":[],"padding":"""")
                        repeat(2_100) { channel.writeStringUtf8("x".repeat(512)) }
                        channel.writeStringUtf8("\"}")
                    }
                },
            )
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
        val notJson = server.client.post("/api/v1/usage/events") {
            bearerAuth("member-a")
            contentType(ContentType.Application.Json)
            setBody("not json")
        }
        assertEquals(HttpStatusCode.BadRequest, notJson.status)
        val stored = server.driver.fetchInOrg(TestCallers.orgA, Statement.create("SELECT id FROM usage_events"))
        assertEquals(0, stored.getOrThrow().size)
    }

    @Test
    fun theServerRecordsModelUsageAndReadsSpend() = serverTest(modules, overrides) { server ->
        val recorder = server.koin.get<UsageRecorder>()
        val account = Uuid.random()
        recorder.record(modelEvent(1_500, memberId = TestCallers.adminA.memberId).copy(accountId = account))
        recorder.record(
            modelEvent(
                2_500,
                memberId = TestCallers.memberA.memberId,
                name = "gpt-5",
            ).copy(provider = "openrouter"),
        )
        recorder.record(modelEvent(4_000, memberId = null).copy(provider = null))
        recorder.record(modelEvent(8_000, memberId = TestCallers.memberA.memberId, at = TestCallers.epoch - 40.days))
        recorder.record(
            modelEvent(
                16_000,
                orgId = TestCallers.orgB,
                memberId = TestCallers.adminB.memberId,
            ).copy(accountId = account),
        )

        val spend = server.koin.get<SpendReader>()
        val month = TestCallers.epoch - 30.days
        assertEquals(8_000L, spend.spentMicros(TestCallers.orgA, null, month))
        assertEquals(2_500L, spend.spentMicros(TestCallers.orgA, TestCallers.memberA.memberId, month))
        assertEquals(0L, spend.spentMicros(TestCallers.orgA, Uuid.random(), month))
        assertEquals(
            1_500L,
            spend.accountSpentMicros(TestCallers.orgA, account, month),
            "An account is read in its org",
        )
        assertEquals(16_000L, spend.accountSpentMicros(TestCallers.orgB, account, month))

        suspend fun groups(groupBy: String, token: String = "admin-a") =
            server.client.get("$orgA/summary?days=30&groupBy=$groupBy") { bearerAuth(token) }
                .body<UsageSummaryResponse>()
                .groups.associate { it.key to it.costMicros }
        assertEquals(
            mapOf(
                null to 4_000L,
                TestCallers.memberA.memberId.toString() to 2_500L,
                TestCallers.adminA.memberId.toString() to 1_500L,
            ),
            groups("member"),
        )
        assertEquals(mapOf<String?, Long>("MODEL" to 8_000L), groups("kind"))
        assertEquals(mapOf<String?, Long>("claude-sonnet-4-5" to 5_500L, "gpt-5" to 2_500L), groups("model"))
        assertEquals(mapOf("anthropic" to 1_500L, "openrouter" to 2_500L, null to 4_000L), groups("provider"))
        assertEquals(mapOf(null to 6_500L, account.toString() to 1_500L), groups("account"))
        assertEquals(
            mapOf<
                String?,
                Long,
                >(TestCallers.memberA.memberId.toString() to 2_500L),
            groups("member", "member-a"),
            "A member sees only themselves",
        )

        val summary = server.client.get("$orgA/summary?days=30") { bearerAuth("admin-a") }.body<UsageSummaryResponse>()
        assertEquals(8_000L, summary.total.costMicros)
        assertEquals(300L, summary.total.inputTokens)
    }

    @Test
    fun malformedQueriesAreRefused() = serverTest(modules, overrides) { server ->
        assertEquals(
            HttpStatusCode.BadRequest,
            server.client.get("$orgA/summary?days=91") { bearerAuth("admin-a") }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            server.client.get("$orgA/summary?groupBy=team") { bearerAuth("admin-a") }.status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            server.client.get("$orgA/events?before=soon") { bearerAuth("admin-a") }.status,
        )
        // Instants the parser reads but PostgreSQL cannot store: a 400, never the database's 500.
        for (at in listOf("%2B300000-01-01T00:00:00Z", "-300000-01-01T00:00:00Z")) {
            assertEquals(
                HttpStatusCode.BadRequest,
                server.client.get("$orgA/events?before=$at") { bearerAuth("admin-a") }.status,
                at,
            )
        }
    }

    @Test
    fun theRepositoryPagesTiesByIdAndPurgesInBatches() = serverTest(modules, overrides) { server ->
        val repository = server.koin.get<UsageRepository>()
        val sameInstant = List(3) { modelEvent(it.toLong()).copy(launchId = "lch_$it".takeIf { _ -> it > 0 }) }
        repository.insert(TestCallers.orgA, sameInstant + modelEvent(9, at = TestCallers.epoch - 500.days))
        repository.insert(TestCallers.orgA, emptyList())

        val first = repository.list(TestCallers.orgA, null, null, 2)
        val second = repository.list(TestCallers.orgA, null, UsageCursor(first.last().at, first.last().id), 2)
        assertEquals(sameInstant.map { it.id }.toSet(), (first + second.take(1)).map { it.id }.toSet())
        assertEquals(9L, second.last().costMicros)
        assertEquals(
            listOf(9L),
            repository.list(TestCallers.orgA, null, UsageCursor(TestCallers.epoch, null), 10).map { it.costMicros },
        )
        val adminEvents = repository.list(TestCallers.orgA, TestCallers.adminA.memberId, null, 10)
        assertEquals(sameInstant.first(), adminEvents.first { it.id == sameInstant.first().id })
        assertEquals(sameInstant.last(), adminEvents.first { it.id == sameInstant.last().id }, "The launch is kept")
        assertEquals(UsageKind.MODEL, first.first().kind)
        val today = UsageWindow(TestCallers.orgA, TestCallers.adminA.memberId, TestCallers.epoch - 1.days)
        assertEquals(1, repository.summary(today, UsageGroupBy.KIND, 10).size)
        val past = UsageWindow(TestCallers.orgA, null, TestCallers.epoch - 600.days, until = TestCallers.epoch - 1.days)
        assertEquals(
            listOf(9L),
            repository.summary(past, UsageGroupBy.KIND, 10).map {
                it.costMicros
            },
            "until excludes",
        )

        // The old retention setting opens nothing any more (RLS audit R4): only usage_purge deletes across orgs.
        val opened = server.driver.transaction {
            execute("SELECT set_config('ostrom.usage_retention', 'on', true)").getOrThrow()
            execute("DELETE FROM usage_events").getOrThrow()
        }
        assertEquals(0L, opened)
        assertEquals(1L, repository.purge(TestCallers.epoch - 400.days, 10))
        assertEquals(0L, repository.purge(TestCallers.epoch - 400.days, 10))
        assertEquals(3, repository.list(TestCallers.orgA, null, null, 10).size)
    }

    @Test
    fun aLaunchSpendIsReadInItsWindowAndItsOrganizationOnly() = serverTest(modules, overrides) { server ->
        val repository = server.koin.get<UsageRepository>()
        val launched = modelEvent(2_500_000).copy(launchId = "lch_9")
        val before = modelEvent(1).copy(launchId = "lch_9", at = launched.at - 1.days)
        repository.insert(TestCallers.orgA, listOf(launched, before))

        val day = launched.at + 1.days
        assertEquals(2_500_000L, repository.launchSpentMicros(TestCallers.orgA, "lch_9", launched.at, day))
        assertEquals(null, repository.launchSpentMicros(TestCallers.orgA, "lch_9", day, day + 1.days), "Window")
        assertEquals(null, repository.launchSpentMicros(TestCallers.orgB, "lch_9", launched.at, day), "Org B")
    }

    @Test
    fun anotherOrgNeitherSeesNorReadsTheUsage() = serverTest(modules, overrides) { server ->
        server.koin.get<UsageRecorder>().record(modelEvent(1_000))

        val own = server.client.get("$orgB/summary?days=1") { bearerAuth("admin-b") }.body<UsageSummaryResponse>()
        assertEquals(emptyList(), own.groups, "Org B's admin reads org B, which has nothing")
        assertEquals(
            emptyList(),
            server.client.get("$orgB/events") { bearerAuth("admin-b") }.body<UsageEventsPageResponse>().events,
        )
        assertEquals(0.0, server.client.get(orgB) { bearerAuth("admin-b") }.body<UsageResponse>().usd)
        server.declare("admin-b", UsageEventRequest("skill", "org-b-skill"))
        val orgAEvents = server.client.get("$orgA/events") { bearerAuth("admin-a") }
            .body<UsageEventsPageResponse>().events
        assertEquals(
            listOf("claude-sonnet-4-5"),
            orgAEvents.map { it.name },
            "Org B's declaration never lands in org A",
        )
    }

    @Test
    fun rowLevelSecurityConfinesEveryQueryToItsOrg() = serverTest(modules, overrides) { server ->
        val repository = server.koin.get<UsageRepository>()
        repository.insert(TestCallers.orgA, listOf(modelEvent(1_000)))
        val count = Statement.create("SELECT COUNT(*) AS n FROM usage_events")
        suspend fun visibleTo(org: Uuid) = server.driver.fetchInOrg(org, count).getOrThrow().first().get("n").asLong()
        assertEquals(1L, visibleTo(TestCallers.orgA))
        assertEquals(0L, visibleTo(TestCallers.orgB), "Even a query without an org filter sees only its org")
        assertFails("A row of another org is refused") {
            repository.insert(TestCallers.orgB, listOf(modelEvent(5, orgId = TestCallers.orgA)))
        }
        assertEquals(
            0L,
            server.driver.executeInOrg(TestCallers.orgB, Statement.create("DELETE FROM usage_events"))
                .getOrThrow(),
            "Nor deleted by another org",
        )
        assertEquals(1L, visibleTo(TestCallers.orgA))
    }

    @Test
    fun theReportSumsAPeriodPerMemberAndPerModel() = serverTest(modules, overrides) { server ->
        val recorder = server.koin.get<UsageRecorder>()
        recorder.record(modelEvent(1_000_000, memberId = TestCallers.adminA.memberId))
        recorder.record(modelEvent(2_500_000, memberId = TestCallers.memberA.memberId, name = "gpt-5"))
        recorder.record(modelEvent(500_000, memberId = null))
        recorder.record(
            modelEvent(9_000_000, memberId = TestCallers.memberA.memberId, at = TestCallers.epoch - 40.days),
        )
        server.declare("member-a", UsageEventRequest("skill", "deploy"))

        val all = server.client.get(orgA) { bearerAuth("admin-a") }.body<UsageResponse>()
        assertEquals((TestCallers.epoch - 30.days).toString(), all.from)
        assertEquals(TestCallers.epoch.toString(), all.to)
        assertEquals(4.0, all.usd)
        assertEquals(4L, all.events, "Three model calls and the skill of the last 30 days")
        assertEquals(
            mapOf(
                TestCallers.adminA.memberId.toString() to 1.0,
                TestCallers.memberA.memberId.toString() to 2.5,
                "unattributed" to 0.5,
            ),
            all.byMember,
        )
        assertEquals(mapOf("claude-sonnet-4-5" to 1.5, "gpt-5" to 2.5), all.byModel)

        val longer = server.client.get("$orgA?from=2026-09-01&to=2026-10-30T13:00:00Z") { bearerAuth("admin-a") }
        assertEquals(13.0, longer.body<UsageResponse>().usd)
        val one = server.client.get(orgA) {
            url.parameters.append("member", TestCallers.memberA.memberId.toString())
            bearerAuth("admin-a")
        }.body<UsageResponse>()
        assertEquals(2.5, one.usd, "An admin may read one member")
        val mine = server.client.get(orgA) {
            url.parameters.append("member", TestCallers.adminA.memberId.toString())
            bearerAuth("member-a")
        }.body<UsageResponse>()
        assertEquals(2.5, mine.usd, "A member always reads their own usage")

        for (query in listOf(
            "from=soon",
            "to=later",
            "member=me",
            "from=2026-10-30&to=2026-10-01",
            "from=2025-01-01",
        )) {
            assertEquals(
                HttpStatusCode.BadRequest,
                server.client.get("$orgA?$query") { bearerAuth("admin-a") }.status,
                query,
            )
        }
    }

    @Test
    fun eachRouteChecksTheCredentialAndItsScope() = serverTest(modules, overrides) { server ->
        val reads = listOf(orgA, "$orgA/summary", "$orgA/events", "$orgA/live")
        for (path in reads) {
            assertEquals(HttpStatusCode.Unauthorized, server.client.get(path).status, path)
            assertEquals(HttpStatusCode.NotFound, server.client.get(path) { bearerAuth("stranger") }.status, path)
            for (token in listOf("device-a", "agent-a", "cli-write", "cli-other")) {
                assertEquals(HttpStatusCode.Forbidden, server.client.get(path) { bearerAuth(token) }.status, token)
            }
        }
        assertEquals(HttpStatusCode.OK, server.client.get("$orgA/summary") { bearerAuth("cli-read") }.status)

        val skill = UsageEventRequest("skill", "deploy")
        assertEquals(HttpStatusCode.NotFound, server.declare("stranger", skill).status)
        for (token in listOf("device-a", "cli-read", "cli-other")) {
            assertEquals(HttpStatusCode.Forbidden, server.declare(token, skill).status, token)
        }
        for (token in listOf("agent-a", "cli-write")) {
            assertEquals(HttpStatusCode.Accepted, server.declare(token, skill).status, "$token records events")
        }
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
                recorder.record(
                    modelEvent(
                        222,
                        memberId = TestCallers.memberA.memberId,
                        at = TestCallers.epoch + 1.minutes,
                    ),
                )
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
                assertTrue(
                    "\"costMicros\":333" in requireNotNull(stream.readUTF8Line()),
                    "An admin follows every member",
                )
            }
        }
    }
}
