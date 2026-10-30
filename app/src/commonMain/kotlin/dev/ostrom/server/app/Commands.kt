// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.app

import dev.ostrom.server.kernel.config.ConfigSource
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.kernel.module.TestAccountSeeder
import org.koin.core.Koin

/** What `ostrom-server` was asked to do. */
sealed interface Command {
    /** Serve HTTP (no argument, or `serve`). */
    data object Serve : Command

    /** Apply every module's migrations with the owner's credentials, then exit (`migrate`). */
    data object Migrate : Command

    /**
     * Create a QA account and print it (`seed-test-account <email>`).
     *
     * @property email The account's e-mail address.
     */
    data class SeedTestAccount(val email: String) : Command
}

/** A command line or a command the server refuses: exit code 2, with this message. */
class UsageException(message: String) : IllegalArgumentException(message)

/** The usage line printed with a [UsageException]. */
const val USAGE: String = "usage: ostrom-server [serve | migrate | seed-test-account <email>]"

private val EMAIL = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

/**
 * Reads the command line.
 *
 * @param args The arguments, without the program name.
 *
 * @return The command.
 *
 * @throws UsageException When the arguments name no command.
 */
fun parseCommand(args: List<String>): Command = when {
    args.isEmpty() || args == listOf("serve") -> Command.Serve
    args == listOf("migrate") -> Command.Migrate
    args.size == 2 && args[0] == "seed-test-account" -> Command.SeedTestAccount(args[1])
    else -> throw UsageException(USAGE)
}

/**
 * Creates a QA account through the module that binds [TestAccountSeeder] and returns it as one JSON line, the token
 * included: the command's output is for the test harness that asked for it, never a log.
 *
 * @param koin The server's Koin, every module loaded and migrations verified.
 * @param source The configuration: `OSTROM_ALLOW_TEST_ACCOUNTS` must be `true`.
 * @param email The account's e-mail address.
 *
 * @return The account, as JSON.
 *
 * @throws UsageException When test accounts are not allowed, the e-mail is not one, or no module creates accounts.
 */
suspend fun seedTestAccount(koin: Koin, source: ConfigSource, email: String): String {
    val refusal = when {
        source["OSTROM_ALLOW_TEST_ACCOUNTS"] != "true" ->
            "Test accounts are refused unless OSTROM_ALLOW_TEST_ACCOUNTS=true: never in production."
        !EMAIL.matches(email) -> "'$email' is not an e-mail address."
        else -> null
    }
    if (refusal != null) throw UsageException(refusal)
    val seeder = koin.getOrNull<TestAccountSeeder>()
        ?: throw UsageException("No module creates test accounts yet: the identity module binds TestAccountSeeder.")
    return OstromJson.encodeToString(seeder.seed(email))
}
