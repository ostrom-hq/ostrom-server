// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.identity.infrastructure

import run.ostrom.server.identity.domain.LinkedIdentities
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement

/**
 * [LinkedIdentities] on PostgreSQL: one read of `identity_identities` by the user's login.
 *
 * @param driver The database.
 */
class LinkedIdentitiesImpl(private val driver: Driver) : LinkedIdentities {
    override suspend fun subjectsOf(login: String, issuer: String): List<String> = driver.fetchAll(
        Statement.create(
            "SELECT i.subject FROM identity_users u JOIN identity_identities i ON i.user_id = u.id " +
                "WHERE u.login = :login AND i.issuer = :issuer ORDER BY i.subject LIMIT 50",
        ).bind("login", login.lowercase()).bind("issuer", issuer),
    ).getOrThrow().map { it.get("subject").asString() }
}
