// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.RateLimitWindows
import run.ostrom.server.identity.domain.RateLimiter
import run.ostrom.server.identity.domain.SecretHasher
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.databaseNow
import run.ostrom.server.kernel.database.databaseNowPlus
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.statements.ReturningStatement
import org.jetbrains.exposed.v1.core.statements.StatementBuilder
import org.jetbrains.exposed.v1.core.statements.buildStatement
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.statements.ReturningBlockingExecutable
import kotlin.time.Duration

/**
 * [RateLimiter] on PostgreSQL through Exposed ([IdentityRateLimits], [IdentityMigrations] version 18): fixed windows
 * shared by every replica, so the limit is the configured one however many replicas serve, and timed by the
 * database's clock, so a replica's clock never moves a window. An attempt is one statement, under the key's row lock:
 * two replicas counting the same key take turns. The keys are stored hashed ([SecretHasher]): an e-mail address or a
 * client address never lands in the table.
 *
 * @param database The JDBC pool, as the serving role (the table is no organization's).
 * @param hasher Hashes the keys.
 */
class RateLimiterImpl(private val database: JdbcDatabase, private val hasher: SecretHasher) :
    RateLimiter, RateLimitWindows {
    override suspend fun tryAcquire(key: String, limit: Int, window: Duration): Boolean {
        val keyHash = hasher.hash(key)
        val seconds = window.inWholeSeconds.coerceAtLeast(1)
        val attempts = database.query {
            ReturningBlockingExecutable(buildStatement { attempt(keyHash, seconds) }).single()[IdentityRateLimits.attempts]
        }
        return attempts <= limit
    }

    override suspend fun purgeExpired(): Long =
        database.query { IdentityRateLimits.deleteWhere(op = windowsOver).toLong() }
}

/**
 * One attempt on a key: the first of a window opens it for [seconds] from now (database clock) and counts 1; the next
 * ones before its end add 1; the first after its end opens a new one. Its row holds the count after this attempt.
 *
 * `INSERT … VALUES (?, 1, now() + make_interval(0, 0, 0, 0, 0, 0, ?)) ON CONFLICT (key_hash) DO UPDATE SET
 * attempts = CASE WHEN ends_at <= now() THEN 1 ELSE attempts + 1 END, ends_at = CASE WHEN ends_at <= now() THEN
 * EXCLUDED.ends_at ELSE ends_at END RETURNING attempts`.
 *
 * @param keyHash The hash of the key.
 * @param seconds The window, in seconds.
 */
internal fun StatementBuilder.attempt(keyHash: String, seconds: Long): ReturningStatement {
    val t = IdentityRateLimits
    val over = t.endsAt lessEq databaseNow()
    return t.upsertReturning(
        t.keyHash,
        returning = listOf(t.attempts),
        onUpdate = {
            it[t.attempts] = case().When(over, intLiteral(1)).Else(t.attempts + 1)
            it[t.endsAt] = case().When(over, insertValue(t.endsAt)).Else(t.endsAt)
        },
    ) {
        it[this.keyHash] = keyHash
        it[attempts] = 1
        it[endsAt] = databaseNowPlus(seconds)
    }
}

/** The windows already over, which no attempt reads again: `DELETE … WHERE ends_at <= now()`. */
internal val windowsOver: IdentityRateLimits.() -> Op<Boolean> = { endsAt lessEq databaseNow() }
