// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

/**
 * The tables of [GatewayMigrations], as Exposed reads and writes them. The migrations create the schema; these objects
 * only name its columns and their types, and never create anything (no `SchemaUtils`). Every one is a tenant table
 * (version 2 and later): read and written in `JdbcDatabase.inOrg`, each query filtered on `org_id` too.
 */
internal object GatewaySettingsTable : Table("gateway_settings") {
    val orgId = uuid("org_id")

    /** The ids of the sealed secrets that hold the org's provider keys, null when unset. */
    val anthropicKeySecret = text("anthropic_key_secret").nullable()
    val openaiKeySecret = text("openai_key_secret").nullable()
    val openrouterKeySecret = text("openrouter_key_secret").nullable()
    val useSharedAccounts = bool("use_shared_accounts")

    /** JSON: a member's id to their most concurrent calls (version 3). */
    val memberConcurrency = text("member_concurrency")

    override val primaryKey = PrimaryKey(orgId)
}

/** The org's model policy; its JSON columns are written and read by the repository's serializers. */
internal object GatewayModelPolicies : Table("gateway_model_policies") {
    val orgId = uuid("org_id")

    /** JSON: the models allowed, every model when empty. */
    val allowed = text("allowed")
    val defaultModel = text("default_model").nullable()

    /** JSON: a member's id to the models they may use. */
    val byMember = text("by_member")

    override val primaryKey = PrimaryKey(orgId)
}

/** One call of the admission queue, from its arrival to its end (version 3): `running` once admitted. */
internal object GatewayAdmissions : Table("gateway_admissions") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val memberId = uuid("member_id")
    val scope = text("scope")

    /** A [run.ostrom.server.gateway.domain.Workload] name. */
    val workload = text("workload")
    val running = bool("running")
    val createdAt = instant("created_at")
    val readyAt = instant("ready_at")
    val polledAt = instant("polled_at")
    val expiresAt = instant("expires_at")

    /** The turn a running call took, and its member's turn before it: set together, while it runs. */
    val turnTaken = long("turn_taken").nullable()
    val turnBefore = long("turn_before").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** Each member's last turn per scope (version 3). */
internal object GatewayAdmissionTurns : Table("gateway_admission_turns") {
    val orgId = uuid("org_id")
    val scope = text("scope")
    val memberId = uuid("member_id")
    val turn = long("turn")

    override val primaryKey = PrimaryKey(orgId, scope, memberId)
}

/** The cancellation markers of inference calls (version 4). */
internal object GatewayCancellations : Table("gateway_cancellations") {
    val orgId = uuid("org_id")
    val memberId = uuid("member_id")
    val requestId = uuid("request_id")
    val expiresAt = instant("expires_at")

    override val primaryKey = PrimaryKey(orgId, memberId, requestId)
}
