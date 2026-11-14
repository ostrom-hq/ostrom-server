// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.SetupLink
import run.ostrom.server.identity.domain.SetupLinksRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [SetupLinksRepository] on PostgreSQL through Exposed ([IdentitySetupLinks], migration 17); an instance-level table.
 *
 * @param database The JDBC pool.
 */
class SetupLinksRepositoryImpl(private val database: JdbcDatabase) : SetupLinksRepository {
    private val t = IdentitySetupLinks

    override suspend fun replace(link: SetupLink, tokenHash: String) {
        database.query {
            // The links still pending end now: one setup link at a time.
            t.update({ t.usedAt.isNull() and (t.expiresAt greater link.createdAt) }) { it[expiresAt] = link.createdAt }
            t.insert {
                it[id] = link.id
                it[this.tokenHash] = tokenHash
                it[createdAt] = link.createdAt
                it[expiresAt] = link.expiresAt
            }
        }
    }

    override suspend fun consume(tokenHash: String, userId: Uuid, now: Instant): Boolean = database.query {
        t.update({ (t.tokenHash eq tokenHash) and t.usedAt.isNull() and (t.expiresAt greater now) }) {
            it[usedAt] = now
            it[usedBy] = userId
        } == 1
    }

    override suspend fun pending(tokenHash: String, now: Instant): Boolean = database.query {
        !t.select(t.id)
            .where { (t.tokenHash eq tokenHash) and t.usedAt.isNull() and (t.expiresAt greater now) }
            .limit(1)
            .empty()
    }

    override suspend fun usedBy(userId: Uuid): Boolean = database.query {
        !t.select(t.id).where { t.usedBy eq userId }.limit(1).empty()
    }

    override suspend fun anyAccount(): Boolean = database.query {
        !IdentityUsers.select(IdentityUsers.id).limit(1).empty()
    }

    // The accounts a setup link created. No LIMIT, on purpose: a cut would silently drop an administrator from the
    // instance's owners. The set is bounded by construction (SetupLinksRepository.anyAccount): only an operator's
    // `ostrom-server bootstrap` makes a setup link, and it makes none once the instance has an account, so only the
    // links made before the first sign-up can ever be used, a handful at most.
    override suspend fun owners(): Map<Uuid, Instant> = database.query {
        t.select(t.usedBy, t.usedAt)
            .where { t.usedBy.isNotNull() }
            .orderBy(t.usedAt)
            .associate { it.present(t.usedBy, t.usedAt) to it.present(t.usedAt, t.usedBy) }
    }
}
