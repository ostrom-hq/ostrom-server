// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.sharing.domain.SharingOrgs
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * [SharingOrgs] on PostgreSQL: the `sharing_orgs_with_accounts()` function ([SharingMigrations] version 12), owned by
 * the router role, which reads only the accounts' `org_id` across organizations. A typed Exposed call, never an SQL
 * string (the database audit of 18/11, TeamCode's raw SQL ban).
 *
 * @param database The database.
 */
class SharingOrgsImpl(private val database: JdbcDatabase) : SharingOrgs {
    override suspend fun withAccounts(): List<Uuid> = database.query {
        // A set-returning function in the select list: one row per organization.
        val orgId = CustomFunction("sharing_orgs_with_accounts", UuidColumnType())
        Table.Dual.select(orgId).map { it[orgId] }
    }
}
