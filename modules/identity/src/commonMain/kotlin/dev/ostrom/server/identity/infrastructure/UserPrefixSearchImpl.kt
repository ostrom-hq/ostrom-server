// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity.infrastructure

import dev.ostrom.server.identity.domain.UserPrefixSearch
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.uuid.Uuid

/**
 * [UserPrefixSearch] on PostgreSQL: one query, the users read by primary key from a single array parameter (a team of
 * 5 000 is one bound value, not 5 000), then filtered on the prefix (OrgsReadsBenchTest).
 *
 * @param driver The connection pool.
 */
class UserPrefixSearchImpl(private val driver: Driver) : UserPrefixSearch {
    override suspend fun matching(ids: Collection<Uuid>, prefix: String): Set<Uuid> {
        // The prefix is a value, never a pattern: its own wildcards are escaped.
        val pattern = prefix.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val statement = Statement.create(
            "SELECT u.id FROM identity_users u WHERE u.id = ANY(CAST(string_to_array(:ids, ',') AS uuid[])) " +
                "AND (lower(u.login) LIKE :pattern OR lower(u.display_name) LIKE :pattern)",
        ).bind("ids", ids.joinToString(",")).bind("pattern", pattern)
        return driver.fetchAll(statement).getOrThrow().map { it.get("id").asUuid() }.toSet()
    }
}
