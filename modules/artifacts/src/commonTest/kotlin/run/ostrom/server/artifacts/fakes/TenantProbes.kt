// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.fakes

import run.ostrom.server.artifacts.infrastructure.TENANT_ROLE
import run.ostrom.server.artifacts.infrastructure.TENANT_SETTING
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import kotlin.uuid.Uuid

// The tests' own probes of the row-level security policies, through the sqlx4k driver of the test server: what a
// query confined to one organization (as the tenant role, with the setting) sees, whatever the repositories say.

/** Runs [block] in a transaction confined to [orgId]: the tenant role and the setting, as the repositories' `inOrg`. */
internal suspend fun <T> Driver.inTenant(orgId: Uuid, block: suspend Transaction.() -> T): T = transaction {
    execute("SET LOCAL ROLE $TENANT_ROLE").getOrThrow()
    execute(
        Statement.create("SELECT set_config('$TENANT_SETTING', :org, true)").bind("org", orgId.toString()),
    ).getOrThrow()
    block()
}

/** One query confined to [orgId] (see [inTenant]). */
internal suspend fun Driver.fetchInOrg(orgId: Uuid, statement: Statement): Result<ResultSet> =
    inTenant(orgId) { Result.success(fetchAll(statement).getOrThrow()) }
