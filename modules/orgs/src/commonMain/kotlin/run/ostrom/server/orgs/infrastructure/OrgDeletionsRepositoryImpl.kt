// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.orgs.domain.OrgDeletionsRepository
import org.jetbrains.exposed.v1.core.Cast
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.concat
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [OrgDeletionsRepository] on PostgreSQL through Exposed. The lists go through the `SECURITY DEFINER` functions of
 * migration 3, which return ids only; each change runs inside the organization it changes.
 *
 * @param database The serving role's pool.
 */
class OrgDeletionsRepositoryImpl(private val database: JdbcDatabase) : OrgDeletionsRepository {
    // Across organizations: the serving role reaches the deleted ones only through these functions.
    override suspend fun deletionsToSettle(now: Instant, limit: Int): List<Uuid> = database.query {
        val t = OrgsDeletionsToSettle
        t.of(now, limit).select(t.id).map { it[t.id] }
    }

    override suspend fun markSettled(id: Uuid, at: Instant) {
        database.inOrg(id) {
            val o = OrgOrganizations
            o.update({ o.id eq id }) {
                it[deletionSettledAt] = at
                it[deletionRetryAt] = null
            }
        }
    }

    override suspend fun duePurges(now: Instant, limit: Int): List<Uuid> = database.query {
        val t = OrgsDuePurges
        t.of(now, limit).select(t.id).map { it[t.id] }
    }

    override suspend fun erase(id: Uuid, at: Instant) {
        database.inOrg(id) {
            val o = OrgOrganizations
            // `'~' || CAST(id AS TEXT)`, as the migration that erased the first ones ([PURGED_SLUG]).
            val purgedSlug = concat(stringLiteral("~"), Cast(o.id, TextColumnType()))
            o.update({ (o.id eq id) and o.deletedAt.isNotNull() }) {
                it[name] = ""
                it[slug] = purgedSlug
                it[githubOrgId] = null
                it[timeZone] = "UTC"
                it[purgedAt] = at
                it[deletionRetryAt] = null
            }
        }
    }

    override suspend fun postpone(id: Uuid, until: Instant) {
        database.inOrg(id) {
            val o = OrgOrganizations
            o.update({ o.id eq id }) { it[deletionRetryAt] = until }
        }
    }
}
