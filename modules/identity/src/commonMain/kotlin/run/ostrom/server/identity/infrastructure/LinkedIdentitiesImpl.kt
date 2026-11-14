// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.LinkedIdentities
import run.ostrom.server.kernel.database.JdbcDatabase
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select

/**
 * [LinkedIdentities] on PostgreSQL through Exposed: one read of `identity_identities` by the user's login.
 *
 * @param database The JDBC pool.
 */
class LinkedIdentitiesImpl(private val database: JdbcDatabase) : LinkedIdentities {
    override suspend fun subjectsOf(login: String, issuer: String): List<String> = database.query {
        val u = IdentityUsers
        val i = IdentityIdentities
        u.join(i, JoinType.INNER, u.id, i.userId)
            .select(i.subject)
            .where { (u.login eq login.lowercase()) and (i.issuer eq issuer) }
            .orderBy(i.subject)
            .limit(MAX_SUBJECTS)
            .map { it[i.subject] }
    }

    private companion object {
        const val MAX_SUBJECTS = 50
    }
}
