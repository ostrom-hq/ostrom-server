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

    /** The launch the call belongs to (version 6), counted against its `maxConcurrent`. */
    val launchId = text("launch_id").nullable()

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

/** One row of an org's price table (version 7): USD millionths per million tokens, from a UTC day on. */
internal object GatewayApiPrices : Table("gateway_api_prices") {
    val orgId = uuid("org_id")
    val provider = text("provider")
    val model = text("model")
    val effectiveFromDay = long("effective_from_day")
    val inputMicros = long("input_micros")
    val cacheWriteMicros = long("cache_write_micros")
    val cacheReadMicros = long("cache_read_micros")
    val outputMicros = long("output_micros")

    override val primaryKey = PrimaryKey(orgId, provider, model, effectiveFromDay)
}

/** The capacity controller's settings of each org (version 8). */
internal object GatewayPacingSettings : Table("gateway_pacing_settings") {
    val orgId = uuid("org_id")
    val observationOnly = bool("observation_only")
    val autoPoolSize = bool("auto_pool_size")

    override val primaryKey = PrimaryKey(orgId)
}

/** The controller's advice per member (`scope_key` '') and session (its id's digest) (version 8). */
internal object GatewayDirectives : Table("gateway_directives") {
    val orgId = uuid("org_id")
    val memberId = uuid("member_id")
    val scopeKey = text("scope_key")
    val id = text("id")

    /** A [run.ostrom.server.gateway.domain.Pace] name. */
    val pace = text("pace")
    val maxConcurrent = integer("max_concurrent")
    val minIntervalMs = long("min_interval_ms")
    val message = text("message")
    val createdAt = instant("created_at")
    val expiresAt = instant("expires_at")
    val contextKey = text("context_key")
    val recoveryPace = text("recovery_pace").nullable()
    val recoveryStartedAt = instant("recovery_started_at").nullable()
    val recoveryMinIntervalMs = long("recovery_min_interval_ms").nullable()

    /** When the scope's last call was admitted: the admission spacing reads it, the controller never writes it. */
    val lastAdmittedAt = instant("last_admitted_at").nullable()

    /** The call admitted then: its own admission never spaces it again (put back to wait, or retried). */
    val lastAdmittedRequest = uuid("last_admitted_request").nullable()

    override val primaryKey = PrimaryKey(orgId, memberId, scopeKey)
}

/** The deliveries of a session's advice per stage (version 8). */
internal object GatewayDirectiveDeliveries : Table("gateway_directive_deliveries") {
    val orgId = uuid("org_id")
    val memberId = uuid("member_id")
    val sessionKey = text("session_key")
    val stage = text("stage")
    val directiveId = text("directive_id")
    val claimId = text("claim_id")
    val tokenId = text("token_id")
    val claimedUntil = instant("claimed_until")
    val emittedAt = instant("emitted_at").nullable()
    val updatedAt = instant("updated_at")

    override val primaryKey = PrimaryKey(orgId, memberId, sessionKey, stage)
}

/** The receipts of the advice (version 8). */
internal object GatewayDirectiveReceipts : Table("gateway_directive_receipts") {
    val orgId = uuid("org_id")
    val directiveId = text("directive_id")
    val tokenId = text("token_id")
    val stage = text("stage")
    val receivedAt = instant("received_at")

    override val primaryKey = PrimaryKey(orgId, directiveId, tokenId, stage)
}
