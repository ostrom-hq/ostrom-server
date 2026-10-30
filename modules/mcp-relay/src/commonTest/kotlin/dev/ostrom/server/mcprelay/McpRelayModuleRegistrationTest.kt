// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.mcprelay

import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.config.InvalidConfigException
import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.kernel.logging.LogLevel
import dev.ostrom.server.kernel.logging.Logger
import dev.ostrom.server.mcprelay.domain.ApprovalRequester
import dev.ostrom.server.mcprelay.domain.McpHostAvailability
import dev.ostrom.server.mcprelay.domain.McpRelayRepository
import dev.ostrom.server.mcprelay.domain.McpUpstream
import dev.ostrom.server.mcprelay.domain.NoApprovalRequester
import dev.ostrom.server.mcprelay.domain.RelayMcpMessageUseCase
import dev.ostrom.server.mcprelay.infrastructure.McpHostConfig
import dev.ostrom.server.secrets.domain.RevealSecretsUseCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class McpRelayModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("mcp-relay", McpRelayModule.name)
        koinApplication { modules(McpRelayModule.koinModule) }.close()
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
        )
        for (values in broken) {
            assertFailsWith<InvalidConfigException>(values.toString()) { McpRelayConfig.from(MapConfigSource(values)) }
        }
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
                    single<RevealSecretsUseCase> { FakeReveal() }
                    single(McpRelayModule.HTTP_CLIENT) { HttpClient(MockEngine { respond("") }) }
                },
            )
        }
        assertTrue(app.koin.get<McpHostAvailability>().isConfigured())
        app.koin.get<McpUpstream>()
        app.koin.get<RelayMcpMessageUseCase>()
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
}
