// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.SignInLeftovers
import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [SignInLeftovers] on the identity tables: none belongs to an organization, so the serving role deletes them in
 * plain transactions, table by table, each through its `expires_at` index, [limit] rows of the oldest per transaction
 * until a batch comes back short, so a backlog drains in one pass. A table that fails does not stop the others: the
 * first failure is thrown once every table was tried.
 *
 * @param database The database.
 */
class SignInLeftoversImpl(private val database: JdbcDatabase) : SignInLeftovers {
    override suspend fun purgeExpired(before: Instant, limit: Int): Long {
        var deleted = 0L
        var failure: Exception? = null
        for ((table, id, expiresAt) in EXPIRING) {
            try {
                do {
                    val batch = database.query {
                        val oldest = table.select(id).where { expiresAt less before }.orderBy(expiresAt).limit(limit)
                        table.deleteWhere { id inSubQuery oldest }
                    }
                    deleted += batch
                } while (batch == limit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = failure ?: error
            }
        }
        failure?.let { throw it }
        return deleted
    }

    private companion object {
        val EXPIRING: List<Triple<Table, Column<Uuid>, Column<Instant>>> = listOf(
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
