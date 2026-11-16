// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.fakes

import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.testing.TestServer
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.uuid.Uuid

// The tests' own probes of the row-level security policies, through the JDBC pool of the test server: what a query
// confined to one organization (as the tenant role, with the setting) sees, whatever the repositories say.

/** The rows of [table] that a query confined to [orgId] sees (`inOrg`), with no `WHERE`: the policy alone filters. */
internal suspend fun TestServer.countInOrg(orgId: Uuid, table: Table): Long =
    koin.get<JdbcDatabase>().inOrg(orgId) { table.selectAll().count() }
