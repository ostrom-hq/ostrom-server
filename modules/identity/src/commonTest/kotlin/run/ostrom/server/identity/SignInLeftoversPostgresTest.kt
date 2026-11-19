// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity

import run.ostrom.server.identity.infrastructure.IdentityDeviceAuthorizations
import run.ostrom.server.identity.infrastructure.IdentityDeviceDaemonListings
import run.ostrom.server.identity.infrastructure.IdentityDevices
import run.ostrom.server.identity.infrastructure.IdentityMagicLinks
import run.ostrom.server.identity.infrastructure.IdentityMobileCodes
import run.ostrom.server.identity.infrastructure.IdentityOAuthAttempts
import run.ostrom.server.identity.infrastructure.IdentitySignInLinks
import run.ostrom.server.identity.infrastructure.IdentityUsers
import run.ostrom.server.identity.infrastructure.IdentityWebSessions
import run.ostrom.server.identity.infrastructure.SignInLeftoversImpl
import run.ostrom.server.kernel.config.DatabaseConfig
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.testing.serverTest
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.sql.SQLException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * What expired sign-ins leave is deleted on real PostgreSQL by the serving role (audit SD-07): every table, only the
 * rows expired before the date, at most the batch of each table per pass, the oldest first.
 */
class SignInLeftoversPostgresTest {
    // To the millisecond, as PostgreSQL keeps it (microseconds), so a row's expiry reads back equal.
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
    private val user = Uuid.random()
    private val device = Uuid.random()

    @Test
    fun theRowsExpiredBeforeTheDateAreDeletedFromEveryTableAndTheOthersKept() = serverTest(listOf(IdentityModule)) {
        server ->
        val longAgo = now - 9.days
        val lastWeek = now - 8.days
        val live = now + 1.hours
        server.database.superuserJdbc.query {
            account()
            listOf(longAgo, lastWeek, live).forEach { at -> leftovers(at) }
        }
        val purge = SignInLeftoversImpl(server.koin.get<JdbcDatabase>())

        // Before last week: one row of each table, a batch of one at a time, the oldest first.
        assertEquals(7L, purge.purgeExpired(now - 8.days, limit = 1))
        assertEquals(listOf(lastWeek, live), server.database.superuserJdbc.query { expiries() }.values.distinct())
        // A backlog drains in one pass, batch after batch, until a batch comes back short.
        server.database.superuserJdbc.query { leftovers(now - 10.days) }
        assertEquals(14L, purge.purgeExpired(now - 7.days, limit = 1))
        assertEquals(0L, purge.purgeExpired(now - 7.days, limit = 10), "Nothing left before the date")
        val kept = server.database.superuserJdbc.query { expiries() }
        assertEquals(TABLES.map { it.first.tableName }.toSet(), kept.keys.map { it.first }.toSet())
        assertEquals(listOf(live), kept.values.distinct(), "Live rows are kept")
    }

    @Test
    fun aFailingTableDoesNotStopTheOthersAndTheFirstFailureIsThrownAfterThem() = runTest {
        val database = closedDatabase() ?: return@runTest
        // A closed pool fails every table: each is tried, and the first failure comes out.
        assertFailsWith<SQLException> { SignInLeftoversImpl(database).purgeExpired(now, limit = 10) }
    }

    @Test
    fun aCancelledPurgeStopsAtOnce() = runTest {
        val database = closedDatabase() ?: return@runTest
        assertFailsWith<CancellationException> {
            withContext(Dispatchers.Default) {
                cancel()
                SignInLeftoversImpl(database).purgeExpired(now, limit = 10)
            }
        }
    }

    /** A pool of the test database, already closed; null when there is no test database. */
    private fun closedDatabase(): JdbcDatabase? {
        val url = System.getenv("OSTROM_TEST_DATABASE_URL")
        if (url == null) {
            if (System.getenv("OSTROM_TEST_DATABASE_REQUIRED") == "true") fail("OSTROM_TEST_DATABASE_URL is required")
            return null
        }
        return JdbcDatabase(DatabaseConfig.of(url, null, null, 2), 2).apply { close() }
    }

    private fun JdbcTransaction.account() {
        IdentityUsers.insert {
            it[id] = user
            it[login] = "ada"
            it[displayName] = "Ada"
            it[createdAt] = now
        }
        IdentityDevices.insert {
            it[id] = device
            it[userId] = user
            it[installationId] = "install-1"
            it[name] = "laptop"
            it[createdAt] = now
            it[lastLoginAt] = now
        }
    }

    private fun JdbcTransaction.leftovers(at: Instant) {
        val key = Uuid.random().toHexString()
        IdentityOAuthAttempts.insert {
            it[id] = Uuid.random()
            it[stateHash] = key
            it[provider] = "github"
            it[codeVerifier] = "v"
            it[nonce] = "n"
            it[redirectPath] = "/"
            it[createdAt] = now
            it[expiresAt] = at
        }
        IdentityMagicLinks.insert {
            it[id] = Uuid.random()
            it[tokenHash] = key
            it[email] = "ada@example.com"
            it[redirectPath] = "/"
            it[createdAt] = now
            it[expiresAt] = at
        }
        IdentitySignInLinks.insert {
            it[id] = Uuid.random()
            it[tokenHash] = key
            it[userId] = user
            it[createdAt] = now
            it[expiresAt] = at
        }
        IdentityMobileCodes.insert {
            it[id] = Uuid.random()
            it[codeHash] = key
            it[userId] = user
            it[codeChallenge] = "c"
            it[redirectUri] = "ostrom://signed-in"
            it[createdAt] = now
            it[expiresAt] = at
        }
        IdentityDeviceAuthorizations.insert {
            it[id] = Uuid.random()
            it[deviceCodeHash] = key
            it[userCode] = key.take(8)
            it[clientName] = "ostrom"
            it[status] = "EXPIRED"
            it[createdAt] = now
            it[expiresAt] = at
            it[scopes] = ""
        }
        devices(at, key)
    }

    private fun JdbcTransaction.devices(at: Instant, key: String) {
        IdentityWebSessions.insert {
            it[id] = Uuid.random()
            it[userId] = user
            it[secretHash] = key
            it[createdAt] = now
            it[expiresAt] = at
            it[lastSeenAt] = now
        }
        IdentityDeviceDaemonListings.insert {
            it[id] = Uuid.random()
            it[deviceId] = device
            it[folderId] = "f"
            it[maxResults] = 10
            it[expiresAt] = at
        }
    }

    /** Each table's rows: (table, id) to its expiry, in the order of [TABLES]. */
    private fun JdbcTransaction.expiries(): Map<Pair<String, Uuid>, Instant> =
        TABLES.flatMap { (table, id, expiresAt) ->
            table.select(id, expiresAt).orderBy(expiresAt).map { (table.tableName to it[id]) to it[expiresAt] }
        }.toMap().entries.sortedBy { it.value }.associate { it.key to it.value }

    private companion object {
        val TABLES: List<Triple<Table, Column<Uuid>, Column<Instant>>> = listOf(
            Triple(IdentityOAuthAttempts, IdentityOAuthAttempts.id, IdentityOAuthAttempts.expiresAt),
            Triple(IdentityMagicLinks, IdentityMagicLinks.id, IdentityMagicLinks.expiresAt),
            Triple(IdentitySignInLinks, IdentitySignInLinks.id, IdentitySignInLinks.expiresAt),
            Triple(IdentityMobileCodes, IdentityMobileCodes.id, IdentityMobileCodes.expiresAt),
            Triple(
                IdentityDeviceAuthorizations,
                IdentityDeviceAuthorizations.id,
                IdentityDeviceAuthorizations.expiresAt,
            ),
            Triple(IdentityWebSessions, IdentityWebSessions.id, IdentityWebSessions.expiresAt),
            Triple(
                IdentityDeviceDaemonListings,
                IdentityDeviceDaemonListings.id,
                IdentityDeviceDaemonListings.expiresAt,
            ),
        )
    }
}
