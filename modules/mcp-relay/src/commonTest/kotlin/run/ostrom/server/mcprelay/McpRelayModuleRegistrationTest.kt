// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.mcprelay

import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.InvalidConfigException
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.mcprelay.domain.ApprovalRequester
import run.ostrom.server.mcprelay.domain.HostedAssignment
import run.ostrom.server.mcprelay.domain.ListMemberServersUseCase
import run.ostrom.server.mcprelay.domain.McpCallSlotRepository
import run.ostrom.server.mcprelay.domain.McpHostAvailability
import run.ostrom.server.mcprelay.domain.McpRelayRepository
import run.ostrom.server.mcprelay.domain.McpSharing
import run.ostrom.server.mcprelay.domain.McpUpstream
import run.ostrom.server.mcprelay.domain.NoApprovalRequester
import run.ostrom.server.mcprelay.domain.RelayMcpMessageUseCase
import run.ostrom.server.mcprelay.infrastructure.McpHostConfig
import run.ostrom.server.secrets.domain.RevealSecretsUseCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

class McpRelayModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("mcp-relay", McpRelayModule.name)
        koinApplication { modules(McpRelayModule.koinModule) }.close()
    }

    @Test
    fun theHostAndTheUpstreamsHaveClientsOfTheirOwn() {
        val app = koinApplication {
            modules(McpRelayModule.koinModule, module { single<ConfigSource> { MapConfigSource(emptyMap()) } })
        }
        // The host's client follows no redirect; the upstreams' client checks each connection where it is made.
        val host = app.koin.get<HttpClient>(McpRelayModule.HTTP_CLIENT)
        val upstreams = app.koin.get<HttpClient>(McpRelayModule.UPSTREAM_CLIENT)
        assertFalse(host === upstreams)
        app.close()
    }

    private fun config(vararg values: Pair<String, String>) = McpRelayConfig.from(MapConfigSource(values.toMap()))

    @Test
    fun theConfigurationReadsTheHostThePrivateSwitchAndTheDeadlines() {
        val defaults = config()
        assertNull(defaults.host)
        assertFalse(defaults.allowPrivateUpstreams)
        assertEquals(15.seconds, defaults.deadlines.list)
        assertNull(config("OSTROM_MCP_HOST_URL" to " ", "OSTROM_MCP_HOST_TOKEN" to " ").host, "Blank is unset")
        val set = config(
            "OSTROM_MCP_HOST_URL" to "http://host:8080",
            "OSTROM_MCP_HOST_TOKEN" to "t",
            "OSTROM_MCP_ALLOW_PRIVATE_UPSTREAMS" to "true",
            "OSTROM_MCP_LIST_TIMEOUT_MS" to "2000",
            "OSTROM_MCP_CALL_TIMEOUT_MS" to "3000",
        )
        assertEquals(McpHostConfig("http://host:8080", "t"), set.host)
        assertTrue(set.allowPrivateUpstreams)
        assertEquals(2000.milliseconds to 3000.milliseconds, set.deadlines.list to set.deadlines.call)
        val broken = listOf(
            mapOf("OSTROM_MCP_HOST_URL" to "http://host"),
            mapOf("OSTROM_MCP_HOST_TOKEN" to "t"),
            mapOf("OSTROM_MCP_LIST_TIMEOUT_MS" to "soon"),
            mapOf("OSTROM_MCP_CALL_TIMEOUT_MS" to "999"),
            mapOf("OSTROM_MCP_CALL_TIMEOUT_MS" to "600001"),
        )
        for (values in broken) {
            assertFailsWith<InvalidConfigException>(values.toString()) { McpRelayConfig.from(MapConfigSource(values)) }
        }
    }

    @Test
    fun aSourceThatKeepsBlankValuesStillLeavesTheHostUnset() {
        val raw = object : ConfigSource {
            override fun get(key: String): String? = if (key.startsWith("OSTROM_MCP_HOST_")) " " else null
        }
        assertNull(McpRelayConfig.from(raw).host)
    }

    @Test
    fun aConfiguredHostRunsStdioServersAndABoundApproverIsUsed() {
        val approver = NoApprovalRequester()
        val app = koinApplication {
            modules(
                McpRelayModule.koinModule,
                module {
                    single<ConfigSource> {
                        MapConfigSource(mapOf("OSTROM_MCP_HOST_URL" to "http://h", "OSTROM_MCP_HOST_TOKEN" to "t"))
                    }
                    single<ApprovalRequester> { approver }
                    single<Clock> { TestClock() }
                    single<McpRelayRepository> { InMemoryRelayRepository() }
                    single<McpCallSlotRepository> { InMemoryMcpSlots(TestClock()) }
                    single<RevealSecretsUseCase> { FakeReveal() }
                    single(McpRelayModule.HTTP_CLIENT) { HttpClient(MockEngine { respond("") }) }
                },
            )
        }
        assertTrue(app.koin.get<McpHostAvailability>().isConfigured())
        app.koin.get<McpUpstream>()
        app.koin.get<RelayMcpMessageUseCase>()
        // With a host, the listing warms the member's stdio servers.
        app.koin.get<ListMemberServersUseCase>()
        app.close()
    }

    @Test
    fun theJournalIsPurgedEveryHourAndAFailedPurgeIsLoggedNotFatal() = runTest {
        val purged = mutableListOf<Instant>()
        val repository = object : McpRelayRepository by InMemoryRelayRepository() {
            override suspend fun purgeCalls(before: Instant): Long {
                purged += before
                if (purged.size == 1) error("database down")
                return 0
            }
        }
        val warnings = mutableListOf<String>()
        val logger = object : Logger {
            override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
                if (level == LogLevel.WARN) warnings += message
            }

            override fun with(fields: Map<String, Any?>): Logger = this
        }
        val app = koinApplication {
            modules(
                module {
                    single<McpRelayRepository> { repository }
                    single<Clock> { TestClock() }
                    single<Logger> { logger }
                },
            )
        }
        McpRelayModule.start(backgroundScope, app.koin)
        runCurrent()
        advanceTimeBy(1.hours + 1.milliseconds)
        assertEquals(2, purged.size)
        assertEquals(listOf("mcp journal purge failed"), warnings)
        assertEquals(Relay.epoch - 30.days, purged.first())
        app.close()
    }

    @Test
    fun aConfiguredHostIsSweptEachMinuteAndAnOutageIsLoggedOnce() = runTest {
        var reads = 0
        val repository = object : McpRelayRepository by InMemoryRelayRepository() {
            override suspend fun hostedAssignments(limit: Int): List<HostedAssignment> {
                if (++reads == 1) error("database down")
                return listOf(HostedAssignment(Relay.orgA, Uuid.random(), 1, McpSharing.SHARED, null, Relay.epoch))
            }
        }
        val warnings = mutableListOf<String>()
        val logger = object : Logger {
            override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
                if (level == LogLevel.WARN) warnings += "$message ${fields["outcome"]}"
            }

            override fun with(fields: Map<String, Any?>): Logger = this
        }
        val answers = ArrayDeque(listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.ServiceUnavailable))
        fun app(host: Boolean) = koinApplication {
            modules(
                McpRelayModule.koinModule,
                module {
                    single<ConfigSource> {
                        val values = mapOf("OSTROM_MCP_HOST_URL" to "http://h", "OSTROM_MCP_HOST_TOKEN" to "t")
                        MapConfigSource(if (host) values else mapOf())
                    }
                    single<McpRelayRepository> { repository }
                    single<Clock> { TestClock() }
                    single<Logger> { logger }
                    single(McpRelayModule.HTTP_CLIENT) {
                        // On the test's scheduler, so each minute's call is answered before the next one.
                        val engine = MockEngineConfig().apply {
                            dispatcher = StandardTestDispatcher(testScheduler)
                            addHandler {
                                val status = answers.removeFirstOrNull() ?: HttpStatusCode.OK
                                respond("""{"stopped":0}""", status)
                            }
                        }
                        HttpClient(MockEngine(engine))
                    }
                },
            )
        }
        val without = app(host = false)
        McpRelayModule.start(backgroundScope, without.koin)
        runCurrent()
        assertEquals(0, reads, "No host, no sweep")
        without.koin.get<ListMemberServersUseCase>()
        // The module's singles live in its definitions until their application closes.
        without.close()
        val with = app(host = true)
        McpRelayModule.start(backgroundScope, with.koin)
        repeat(5) {
            runCurrent()
            advanceTimeBy(1.minutes)
        }
        assertEquals(
            listOf("mcp retain sweep Incomplete", "mcp retain sweep HostUnavailable"),
            warnings,
            "Each change of outcome once: the second minute of the outage is not logged again",
        )
        with.close()
    }
}
