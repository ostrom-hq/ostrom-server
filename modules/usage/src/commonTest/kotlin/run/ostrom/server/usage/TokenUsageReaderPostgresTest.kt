// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.usage

import run.ostrom.server.secrets.SecretsModule
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.testing.serverTest
import run.ostrom.server.usage.domain.TokenBucket
import run.ostrom.server.usage.domain.TokenUsageReader
import run.ostrom.server.usage.domain.UsageKind
import run.ostrom.server.usage.domain.UsageRepository
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Real PostgreSQL: the ledger's model tokens by UTC hour of one shared account and by UTC day of the org (the automatic
 * pool size), summed by provider and model, newest first, bounded, and confined to the org.
 */
class TokenUsageReaderPostgresTest {
    private val modules = listOf(SecretsModule, UsageModule)
    private val overrides: Module = module {
        includes(TestCallers.overrides(TestClock()))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
    }
    private val account = Uuid.parse("000000ac-0000-0000-0000-000000000001")
    private val now = TestCallers.epoch
    private val hour = now.toEpochMilliseconds() / 1.hours.inWholeMilliseconds
    private val day = now.toEpochMilliseconds() / 1.days.inWholeMilliseconds

    private fun onAccount(at: Instant, name: String) = modelEvent(1, at = at, name = name).copy(accountId = account)

    @Test
    fun tokensAreSummedByHourOrDayProviderAndModelNewestFirst() = serverTest(modules, overrides) { server ->
        server.koin.get<UsageRepository>().insert(
            TestCallers.orgA,
            listOf(
                onAccount(now - 10.minutes, "claude-opus-5-5"),
                onAccount(now - 20.minutes, "claude-opus-5-5"),
                onAccount(now - 70.minutes, "claude-opus-5-5"),
                onAccount(now - 30.minutes, "claude-haiku-4-5").copy(provider = null),
                // Before the look-back, on another account, and not a model call: none are read.
                onAccount(now - 5.hours, "claude-opus-5-5"),
                modelEvent(1, at = now - 10.minutes),
                modelEvent(1, at = now - 3.days).copy(kind = UsageKind.MCP, name = "server/tool"),
                modelEvent(1, at = now - 2.days),
            ),
        )
        server.koin.get<UsageRepository>().insert(
            TestCallers.orgB,
            listOf(modelEvent(1, orgId = TestCallers.orgB, at = now - 10.minutes).copy(accountId = account)),
        )
        val reader = server.koin.get<TokenUsageReader>()
        val hours = reader.accountHours(TestCallers.orgA, account, now - 2.hours, 100)
        assertEquals(
            listOf(
                TokenBucket(hour - 1, "anthropic", "claude-opus-5-5", 200, 20, 10, 2),
                TokenBucket(hour - 1, null, "claude-haiku-4-5", 100, 10, 5, 1),
                TokenBucket(hour - 2, "anthropic", "claude-opus-5-5", 100, 10, 5, 1),
            ),
            hours.sortedWith(compareByDescending<TokenBucket> { it.bucket }.thenBy { it.provider == null }),
        )
        val bounded = reader.accountHours(TestCallers.orgA, account, now - 2.hours, 1)
        assertEquals(hour - 1, bounded.single().bucket, "Past the bound, the newest hours")
        val days = reader.orgDays(TestCallers.orgA, now - 6.days, 100)
        assertEquals(listOf(day, day - 2), days.map { it.bucket }.distinct())
        assertEquals(600L, days.filter { it.bucket == day }.sumOf { it.input }, "Every model call of the org today")
        assertTrue(reader.accountHours(TestCallers.orgB, Uuid.random(), now - 2.hours, 100).isEmpty())
        assertEquals(1, reader.orgDays(TestCallers.orgB, now - 6.days, 100).size, "B reads only its own")
    }
}
