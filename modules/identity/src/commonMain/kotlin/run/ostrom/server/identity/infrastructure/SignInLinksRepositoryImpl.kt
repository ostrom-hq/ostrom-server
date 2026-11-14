// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.SignInLink
import run.ostrom.server.identity.domain.SignInLinksRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.updateReturning
import kotlin.time.Instant

/**
 * [SignInLinksRepository] on PostgreSQL through Exposed ([IdentitySignInLinks], migration 4).
 *
 * @param database The JDBC pool.
 */
class SignInLinksRepositoryImpl(private val database: JdbcDatabase) : SignInLinksRepository {
    private val t = IdentitySignInLinks

    override suspend fun create(link: SignInLink, tokenHash: String) {
        database.query {
            t.insert {
                it[id] = link.id
                it[this.tokenHash] = tokenHash
                it[userId] = link.userId
                it[createdAt] = link.createdAt
                it[expiresAt] = link.expiresAt
            }
        }
    }

    // One statement marks it used and returns it: two exchanges of the same link cannot both get it.
    override suspend fun consume(tokenHash: String, now: Instant): SignInLink? = database.query {
        t.updateReturning(
            listOf(t.id, t.userId, t.createdAt, t.expiresAt),
            where = { (t.tokenHash eq tokenHash) and t.usedAt.isNull() and (t.expiresAt greater now) },
        ) { it[usedAt] = now }.singleOrNull()?.let {
            SignInLink(id = it[t.id], userId = it[t.userId], createdAt = it[t.createdAt], expiresAt = it[t.expiresAt])
        }
    }
}
