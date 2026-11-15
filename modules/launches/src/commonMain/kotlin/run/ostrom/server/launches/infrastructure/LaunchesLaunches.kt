// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.launches.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

// The table of [LaunchesMigrations], as Exposed reads and writes it: the migrations create the schema, this object
// only names its columns. An organization's, under row-level security.

/** The launches, one per `ostrom launch`, with the labels fixed at start and the limits an administrator sets. */
internal object LaunchesLaunches : Table("launches_launches") {
    val orgId = uuid("org_id")
    val id = text("id")
    val userId = uuid("user_id")
    val dev = text("dev")
    val repo = text("repo").nullable()
    val agent = text("agent")
    val model = text("model").nullable()
    val createdAt = instant("created_at")
    val expiresAt = instant("expires_at")
    val cloudSessionId = uuid("cloud_session_id").nullable()
    val workflowId = text("workflow_id").nullable()
    val labels = text("labels")
    val budgetMicros = long("budget_micros")
    val maxConcurrent = integer("max_concurrent")
    val paused = bool("paused")
    override val primaryKey = PrimaryKey(orgId, id)
}
