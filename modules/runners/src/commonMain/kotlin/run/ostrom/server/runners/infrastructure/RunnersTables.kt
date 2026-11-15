// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.infrastructure

import run.ostrom.server.kernel.database.instant
import org.jetbrains.exposed.v1.core.Table

// The tables of [RunnersMigrations] as Exposed reads and writes them: the migrations create the schema (no
// `SchemaUtils`), these objects only name its columns. The conversation's are in CloudConversationTables.kt, the
// files' in CloudFileTables.kt.

/** The cloud and remote sessions (version 1, and the columns each later version adds). */
internal object RunnersSessionsTable : Table("runners_sessions") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val ownerId = text("owner_id")
    val name = text("name")
    val agent = text("agent")
    val model = text("model").nullable()
    val prompt = text("prompt").nullable()
    val repositoryUrl = text("repository_url").nullable()
    val repositoryRef = text("repository_ref").nullable()
    val state = text("state")
    val desiredState = text("desired_state")
    val phase = text("phase").nullable()
    val epoch = long("epoch")
    val generation = long("generation")
    val failureCode = text("failure_code").nullable()
    val failureMessage = text("failure_message").nullable()
    val idleTimeoutMinutes = integer("idle_timeout_minutes")
    val maxRunMinutes = integer("max_run_minutes")
    val restrictedEgress = bool("restricted_egress")
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")
    val executionStartedAt = instant("execution_started_at").nullable()
    val readyAt = instant("ready_at").nullable()
    val lastHeartbeatAt = instant("last_heartbeat_at").nullable()
    val lastActivityAt = instant("last_activity_at").nullable()
    val pausedAt = instant("paused_at").nullable()
    val terminatedAt = instant("terminated_at").nullable()
    val pausingSince = instant("pausing_since").nullable()
    val runnerStoppedAt = instant("runner_stopped_at").nullable()
    val runnerTokenHash = text("runner_token_hash").nullable()

    // Version 10, 12, 13, 14.
    val githubAccess = text("github_access")
    val backgroundSince = instant("background_since").nullable()
    val backgroundSeenAt = instant("background_seen_at").nullable()
    val environment = text("environment").nullable()
    val failureRetryable = bool("failure_retryable")
    val profile = text("profile").nullable()
    val clusterUnreachableSince = instant("cluster_unreachable_since").nullable()

    // Version 16: the expiry.
    val expiresAt = instant("expires_at").nullable()
    val terminatedReason = text("terminated_reason").nullable()
    val expiryBackfill = bool("expiry_backfill")

    // Version 17: the conversation.
    val mode = text("mode")
    val effort = text("effort").nullable()
    val bypassBy = text("bypass_by").nullable()
    val bypassCredentialId = text("bypass_credential_id").nullable()
    val bypassAt = instant("bypass_at").nullable()
    val waitingReason = text("waiting_reason").nullable()
    val waitingRequestId = text("waiting_request_id").nullable()
    val waitingSince = instant("waiting_since").nullable()
    val waitingEventSeq = long("waiting_event_seq").nullable()
    val waitingEpoch = long("waiting_epoch").nullable()

    // Version 18: the proof that a workspace is gone.
    val workspaceDeletionState = text("workspace_deletion_state").nullable()
    val workspaceClaimDeletedAt = instant("workspace_claim_deleted_at").nullable()
    val workspaceVolumeDeletedAt = instant("workspace_volume_deleted_at").nullable()
    val workspaceVolumeName = text("workspace_volume_name").nullable()
    val workspaceVolumeHandle = text("workspace_volume_handle").nullable()
    val workspaceProblemCode = text("workspace_problem_code").nullable()
    val workspaceProblemMessage = text("workspace_problem_message").nullable()
    val workspaceProblemRetryable = bool("workspace_problem_retryable")

    // Version 20: the transfers.
    val transferId = uuid("transfer_id").nullable()
    val untrustedWorkspace = bool("untrusted_workspace")

    // Version 22, 23, 26: remote sessions and who created a session.
    val kind = text("kind")
    val hostDeviceId = uuid("host_device_id").nullable()
    val hostDirectoryName = text("host_directory_name").nullable()
    val hostPermissionMode = text("host_permission_mode").nullable()
    val hostFolderId = text("host_folder_id").nullable()
    val createdByInitiatorId = text("created_by_initiator_id").nullable()
    val createdByCredentialId = text("created_by_credential_id").nullable()
    val createdByEffectiveMemberId = text("created_by_effective_member_id").nullable()
    val hostResumeSessionId = text("host_resume_session_id").nullable()
    val launchId = text("launch_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** The reconciler's schedule of each session, and the hash of its runner token (versions 1 and 3). */
internal object RunnersSessionRoutes : Table("runners_session_routes") {
    val sessionId = uuid("session_id")
    val orgId = uuid("org_id")
    val runnerTokenHash = text("runner_token_hash").nullable()
    val nextReconcileAt = instant("next_reconcile_at").nullable()
    val reconcileOwner = text("reconcile_owner").nullable()
    val reconcileUntil = instant("reconcile_until").nullable()
    val claimedAt = instant("claimed_at").nullable()

    override val primaryKey = PrimaryKey(sessionId)
}

/** The execution seconds of each organization and month (version 1). */
internal object RunnersUsage : Table("runners_usage") {
    val orgId = uuid("org_id")
    val month = text("month")
    val seconds = long("seconds")

    override val primaryKey = PrimaryKey(orgId, month)
}

/** The `Idempotency-Key` of a session's creation (version 19). */
internal object RunnersCreateKeys : Table("runners_create_keys") {
    val orgId = uuid("org_id")
    val memberId = text("member_id")
    val idempotencyKey = text("idempotency_key")
    val requestDigest = text("request_digest")
    val sessionId = uuid("session_id")
    val createdAt = instant("created_at")
    val expiresAt = instant("expires_at")

    override val primaryKey = PrimaryKey(orgId, memberId, idempotencyKey)
}

/** The runners registered with an organization (versions 2 and 7). */
internal object RunnersRegistered : Table("runners_registered") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val name = text("name")
    val labels = text("labels")
    val version = text("version").nullable()
    val keyHash = text("key_hash")
    val registeredBy = text("registered_by")
    val createdAt = instant("created_at")
    val lastSeenAt = instant("last_seen_at").nullable()
    val capacity = integer("capacity").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** The executions handed to registered runners (version 6). */
internal object RunnersAssignments : Table("runners_assignments") {
    val sessionId = uuid("session_id")
    val epoch = long("epoch")
    val orgId = uuid("org_id")
    val runnerId = uuid("runner_id").nullable()
    val createdAt = instant("created_at")
    val deliveredAt = instant("delivered_at").nullable()
    val stoppedAt = instant("stopped_at").nullable()
    val droppedAt = instant("dropped_at").nullable()
    val state = text("state").nullable()
    val holdsWorkspace = bool("holds_workspace")

    override val primaryKey = PrimaryKey(sessionId, epoch)
}

/** The terminal tickets, by the hash of their value (version 9). */
internal object RunnersTerminalTickets : Table("runners_terminal_tickets") {
    val tokenHash = text("token_hash")
    val orgId = uuid("org_id")
    val sessionId = uuid("session_id")
    val memberId = text("member_id")
    val admin = bool("admin")
    val expiresAt = instant("expires_at")

    override val primaryKey = PrimaryKey(tokenHash)
}

/** Which replica holds each session's runner socket (version 11). */
internal object RunnersTerminalHosts : Table("runners_terminal_hosts") {
    val sessionId = uuid("session_id")
    val orgId = uuid("org_id")
    val epoch = long("epoch")
    val replicaUrl = text("replica_url")
    val expiresAt = instant("expires_at")

    override val primaryKey = PrimaryKey(sessionId)
}

/** The transfers of a session from a member to another (version 20). */
internal object RunnersSessionTransfers : Table("runners_session_transfers") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val sessionId = uuid("session_id")
    val fromMemberId = text("from_member_id")
    val toMemberId = text("to_member_id")
    val initiatedBy = text("initiated_by")
    val state = text("state")
    val resume = bool("resume")
    val version = integer("version")
    val createdAt = instant("created_at")
    val updatedAt = instant("updated_at")
    val expiresAt = instant("expires_at").nullable()
    val decidedBy = text("decided_by").nullable()
    val decidedAt = instant("decided_at").nullable()
    val completedAt = instant("completed_at").nullable()
    val atEpoch = long("at_epoch").nullable()
    val failureCode = text("failure_code").nullable()
    val failureMessage = text("failure_message").nullable()
    val idempotencyKey = text("idempotency_key").nullable()
    val requestDigest = text("request_digest").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** An execution's inference credential, an agent token of its launch (version 21). */
internal object RunnersInferenceCredentials : Table("runners_inference_credentials") {
    val tokenId = uuid("token_id")
    val orgId = uuid("org_id")
    val sessionId = uuid("session_id")
    val epoch = long("epoch")
    val createdAt = instant("created_at")
    val expiresAt = instant("expires_at")

    override val primaryKey = PrimaryKey(tokenId)
}
