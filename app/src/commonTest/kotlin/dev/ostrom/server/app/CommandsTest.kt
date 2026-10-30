// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app

import dev.ostrom.server.kernel.config.MapConfigSource
import dev.ostrom.server.kernel.module.TestAccount
import dev.ostrom.server.kernel.module.TestAccountSeeder
import kotlinx.coroutines.test.runTest
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommandsTest {
    @Test
    fun readsTheCommandLine() {
        assertEquals(Command.Serve, parseCommand(emptyList()))
        assertEquals(Command.Serve, parseCommand(listOf("serve")))
        assertEquals(Command.Migrate, parseCommand(listOf("migrate")))
        assertEquals(
            Command.SeedTestAccount("qa@ostrom.test"),
            parseCommand(listOf("seed-test-account", "qa@ostrom.test")),
        )
    }

    @Test
    fun refusesAnUnknownCommandLine() {
        listOf(listOf("serve", "now"), listOf("migrate", "down"), listOf("seed-test-account"), listOf("help"))
            .forEach { args ->
                assertEquals(USAGE, assertFailsWith<UsageException>("$args") { parseCommand(args) }.message)
            }
    }

    private class FixedSeeder : TestAccountSeeder {
        override suspend fun seed(email: String): TestAccount = TestAccount(email, "ost_test_token", "org-1")
    }

    private val allowed = MapConfigSource(mapOf("OSTROM_ALLOW_TEST_ACCOUNTS" to "true"))

    private val withSeeder = koinApplication { modules(module { single<TestAccountSeeder> { FixedSeeder() } }) }.koin

    @Test
    fun printsTheSeededAccountAsOneJsonLine() = runTest {
        assertEquals(
            """{"email":"qa@ostrom.test","token":"ost_test_token","organizationId":"org-1"}""",
            seedTestAccount(withSeeder, allowed, "qa@ostrom.test"),
        )
    }

    @Test
    fun refusesTestAccountsUnlessAllowed() = runTest {
        val error = assertFailsWith<UsageException> {
            seedTestAccount(withSeeder, MapConfigSource(emptyMap()), "qa@ostrom.test")
        }
        assertEquals(
            "Test accounts are refused unless OSTROM_ALLOW_TEST_ACCOUNTS=true: never in production.",
            error.message,
        )
        assertFailsWith<UsageException> {
            seedTestAccount(withSeeder, MapConfigSource(mapOf("OSTROM_ALLOW_TEST_ACCOUNTS" to "yes")), "qa@ostrom.test")
        }
    }

    @Test
    fun refusesWhatIsNotAnEmailAddress() = runTest {
        listOf("qa", "qa@ostrom", "@ostrom.test", "q a@ostrom.test").forEach { email ->
            assertFailsWith<UsageException>(email) { seedTestAccount(withSeeder, allowed, email) }
        }
    }

    @Test
    fun saysWhenNoModuleCreatesAccounts() = runTest {
        val error = assertFailsWith<UsageException> {
            seedTestAccount(koinApplication {}.koin, allowed, "qa@ostrom.test")
        }
        assertEquals(
            "No module creates test accounts yet: the identity module binds TestAccountSeeder.",
            error.message,
        )
    }
}
