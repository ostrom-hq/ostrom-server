// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs.infrastructure

import dev.ostrom.server.kernel.database.inOrg
import dev.ostrom.server.orgs.domain.OrgDeletionsRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [OrgDeletionsRepository] on PostgreSQL. The lists go through the `SECURITY DEFINER` functions of
 * migration 3, which return ids only; each change runs inside the organization it changes.
 *
 * @param driver The database.
 */
class OrgDeletionsRepositoryImpl(private val driver: Driver) : OrgDeletionsRepository {
    // Across organizations: the serving role reaches the deleted ones only through these functions.
    override suspend fun deletionsToSettle(now: Instant, limit: Int): List<Uuid> =
        driver
            .fetchAll(
                Statement.create(
                        "SELECT id FROM orgs_deletions_to_settle(CAST(:now AS TIMESTAMPTZ), CAST(:limit AS INTEGER)) id"
                    )
                    .bind("now", now)
                    .bind("limit", limit)
            )
            .getOrThrow()
            .map { it.get("id").asUuid() }

    override suspend fun markSettled(id: Uuid, at: Instant) {
        driver.inOrg(id) {
            execute(
                    Statement.create(
                            "UPDATE orgs_organizations SET deletion_settled_at = :at, deletion_retry_at = NULL " +
                                "WHERE id = :id"
                        )
                        .bind("at", at)
                        .bind("id", id)
                )
                .getOrThrow()
        }
    }

    override suspend fun duePurges(now: Instant, limit: Int): List<Uuid> =
        driver
            .fetchAll(
                Statement.create(
                        "SELECT id FROM orgs_due_purges(CAST(:now AS TIMESTAMPTZ), CAST(:limit AS INTEGER)) id"
                    )
                    .bind("now", now)
                    .bind("limit", limit)
            )
            .getOrThrow()
            .map { it.get("id").asUuid() }

    override suspend fun erase(id: Uuid, at: Instant) {
        driver.inOrg(id) {
            execute(
                    Statement.create(
                            "UPDATE orgs_organizations SET name = '', slug = $PURGED_SLUG, github_org_id = NULL, " +
                                "time_zone = 'UTC', purged_at = :at, deletion_retry_at = NULL " +
                                "WHERE id = :id AND deleted_at IS NOT NULL"
                        )
                        .bind("at", at)
                        .bind("id", id)
                )
                .getOrThrow()
        }
    }

    override suspend fun postpone(id: Uuid, until: Instant) {
        driver.inOrg(id) {
            execute(
                    Statement.create("UPDATE orgs_organizations SET deletion_retry_at = :until WHERE id = :id")
                        .bind("until", until)
                        .bind("id", id)
                )
                .getOrThrow()
        }
    }
}
