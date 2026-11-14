// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.MagicLink
import run.ostrom.server.identity.domain.MagicLinksRepository
import run.ostrom.server.identity.domain.MobileClient
import run.ostrom.server.identity.domain.OAuthAttempt
import run.ostrom.server.identity.domain.OAuthAttemptsRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.updateReturning
import kotlin.time.Instant

/**
 * [OAuthAttemptsRepository] on PostgreSQL through Exposed ([IdentityOAuthAttempts]).
 *
 * @param database The JDBC pool.
 */
class OAuthAttemptsRepositoryImpl(private val database: JdbcDatabase) : OAuthAttemptsRepository {
    private val t = IdentityOAuthAttempts

    override suspend fun create(attempt: OAuthAttempt, stateHash: String) {
        database.query {
            t.insert {
                it[id] = attempt.id
                it[this.stateHash] = stateHash
                it[provider] = attempt.provider.key
                it[connectionId] = attempt.connectionId
                it[codeVerifier] = attempt.codeVerifier
                it[nonce] = attempt.nonce
                it[redirectPath] = attempt.redirectPath
                it[createdAt] = attempt.createdAt
                it[expiresAt] = attempt.expiresAt
                it[linkUserId] = attempt.linkUserId
                it[mobileRedirectUri] = attempt.mobile?.redirectUri
                it[mobileCodeChallenge] = attempt.mobile?.codeChallenge
                it[mobileState] = attempt.mobile?.state
            }
        }
    }

    // One statement marks it used and returns it: two callbacks with the same state cannot both get it.
    override suspend fun consume(stateHash: String, now: Instant): OAuthAttempt? = database.query {
        t.updateReturning(
            listOf(
                t.id, t.provider, t.connectionId, t.codeVerifier, t.nonce, t.redirectPath, t.createdAt, t.expiresAt,
                t.linkUserId, t.mobileRedirectUri, t.mobileCodeChallenge, t.mobileState,
            ),
            where = { (t.stateHash eq stateHash) and t.usedAt.isNull() and (t.expiresAt greater now) },
        ) { it[usedAt] = now }.singleOrNull()?.toAttempt()
    }

    private fun ResultRow.toAttempt() = OAuthAttempt(
        id = this[t.id],
        provider = IdentityProvider.of(this[t.provider]),
        connectionId = this[t.connectionId],
        codeVerifier = this[t.codeVerifier],
        nonce = this[t.nonce],
        redirectPath = this[t.redirectPath],
        createdAt = this[t.createdAt],
        expiresAt = this[t.expiresAt],
        linkUserId = this[t.linkUserId],
        // The three are written together: an app's attempt has all of them, a browser's none.
        mobile = this[t.mobileRedirectUri]?.let { uri ->
            MobileClient(uri, present(t.mobileCodeChallenge), present(t.mobileState))
        },
    )
}

/**
 * [MagicLinksRepository] on PostgreSQL through Exposed ([IdentityMagicLinks]).
 *
 * @param database The JDBC pool.
 */
class MagicLinksRepositoryImpl(private val database: JdbcDatabase) : MagicLinksRepository {
    private val t = IdentityMagicLinks

    override suspend fun create(link: MagicLink, tokenHash: String, browserHash: String) {
        database.query {
            t.insert {
                it[id] = link.id
                it[this.tokenHash] = tokenHash
                it[email] = link.email
                it[redirectPath] = link.redirectPath
                it[createdAt] = link.createdAt
                it[expiresAt] = link.expiresAt
                it[this.browserHash] = browserHash
            }
        }
    }

    // A link of before the browser binding (browser_hash NULL) matches no browser: ask for a new one.
    override suspend fun consume(tokenHash: String, browserHash: String, now: Instant): MagicLink? = database.query {
        t.updateReturning(
            listOf(t.id, t.email, t.redirectPath, t.createdAt, t.expiresAt),
            where = {
                (t.tokenHash eq tokenHash) and t.usedAt.isNull() and (t.expiresAt greater now) and
                    (t.browserHash eq browserHash)
            },
        ) { it[usedAt] = now }.singleOrNull()?.let {
            MagicLink(
                id = it[t.id],
                email = it[t.email],
                redirectPath = it[t.redirectPath],
                createdAt = it[t.createdAt],
                expiresAt = it[t.expiresAt],
            )
        }
    }

    override suspend fun pending(tokenHash: String, now: Instant): Boolean = database.query {
        !t.select(t.id)
            .where { (t.tokenHash eq tokenHash) and t.usedAt.isNull() and (t.expiresAt greater now) }
            .limit(1)
            .empty()
    }
}
