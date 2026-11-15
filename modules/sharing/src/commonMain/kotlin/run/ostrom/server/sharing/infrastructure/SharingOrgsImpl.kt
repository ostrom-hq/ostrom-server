// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.infrastructure

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.sharing.domain.SharingOrgs
import kotlin.uuid.Uuid
import kotlin.uuid.toKotlinUuid

/**
 * [SharingOrgs] on PostgreSQL: the `sharing_orgs_with_accounts()` function ([SharingMigrations] version 12), owned by
 * the router role, which reads only the accounts' `org_id` across organizations.
 *
 * @param database The database.
 */
class SharingOrgsImpl(private val database: JdbcDatabase) : SharingOrgs {
    override suspend fun withAccounts(): List<Uuid> = database.query {
        exec("SELECT org_id FROM sharing_orgs_with_accounts() AS org_id") { rows ->
            buildList { while (rows.next()) add(rows.getObject(1, java.util.UUID::class.java).toKotlinUuid()) }
        }.orEmpty()
    }
}
